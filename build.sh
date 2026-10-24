#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
classes_dir="$project_dir/build/classes"
jar_file="$project_dir/build/HyperZebra.jar"

if [[ -n "${JAVA_HOME:-}" ]]; then
	javac_command="$JAVA_HOME/bin/javac"
	jar_command="$JAVA_HOME/bin/jar"
elif [[ -x "/opt/homebrew/opt/openjdk/bin/javac" ]]; then
	javac_command="/opt/homebrew/opt/openjdk/bin/javac"
	jar_command="/opt/homebrew/opt/openjdk/bin/jar"
elif [[ -x "/usr/local/opt/openjdk/bin/javac" ]]; then
	javac_command="/usr/local/opt/openjdk/bin/javac"
	jar_command="/usr/local/opt/openjdk/bin/jar"
else
	javac_command="$(command -v javac)"
	jar_command="$(command -v jar)"
fi

rm -rf "$classes_dir"
mkdir -p "$classes_dir"

# The sources are under src/, one folder per area (src/README.md); they all stay in the default package.
# src/ffm needs Java 22 and is compiled on its own below.
main_sources=()
while IFS= read -r -d '' source_file; do main_sources+=("$source_file"); done \
	< <(find "$project_dir/src" -name '*.java' -not -path "$project_dir/src/ffm/*" -print0)
"$javac_command" --release 17 -encoding UTF-8 -d "$classes_dir" "${main_sources[@]}"
# Optional helpers that need the Foreign Function API (Java 22+): loaded by reflection, skipped on older JDKs
# (compiled against the main classes: the controller sources implement GamepadHost.Source).
if ! "$javac_command" --release 22 -encoding UTF-8 -cp "$classes_dir" -d "$classes_dir" "$project_dir"/src/ffm/*.java 2>/dev/null; then
	echo "FFM helpers (src/ffm/) skipped: javac does not support --release 22"
fi
# The user interface languages (International): the lang/*.po catalogs go into the jar as lang/<tag>.po
mkdir -p "$classes_dir/lang"
cp "$project_dir"/lang/*.po "$classes_dir/lang/"
# Enable-Native-Access lets the FFM helpers call libc without the "restricted method" warning (Java 22+ honours it in the manifest).
# Add-Exports lets MidiHost68k open the JDK synthesizer (com.sun.media.sound) with a short latency for MIDI played live.
printf 'Enable-Native-Access: ALL-UNNAMED\nAdd-Exports: java.desktop/com.sun.media.sound\n' > "$classes_dir/../manifest.txt"
"$jar_command" --create --file "$jar_file" --main-class HyperZebra --manifest "$classes_dir/../manifest.txt" -C "$classes_dir" .

# The translation helper (VMTranslation): the macOS 26 Translation and Foundation Models frameworks are Swift-only, so they
# run in a small Swift program beside the jar. Skipped without swiftc or an SDK that has them (translation is then shown
# as unavailable). Arm and Intel slices when both build, else this Mac's own.
helper_source="$project_dir/native/translate/HZTranslate.swift"
helper="$project_dir/build/hz-translate"
if [[ "$(uname -s)" == "Darwin" && -f "$helper_source" ]] && command -v swiftc >/dev/null 2>&1; then
	if [[ ! -x "$helper" || "$helper_source" -nt "$helper" ]]; then
		helper_tmp="$project_dir/build/hz-translate-build"
		rm -rf "$helper_tmp"; mkdir -p "$helper_tmp"
		slices=()
		for arch in arm64 x86_64; do
			if swiftc -O -parse-as-library -target "$arch-apple-macos26" "$helper_source" -o "$helper_tmp/$arch" 2>"$helper_tmp/$arch.log"; then slices+=("$helper_tmp/$arch"); fi
		done
		if [[ ${#slices[@]} -gt 1 ]]; then lipo -create "${slices[@]}" -output "$helper"
		elif [[ ${#slices[@]} -eq 1 ]]; then cp "${slices[0]}" "$helper"
		else echo "Translation helper skipped (swiftc could not build it; see $helper_tmp/*.log)"; fi
		[[ -x "$helper" ]] && rm -rf "$helper_tmp"
	fi
fi

echo "Built $jar_file"
