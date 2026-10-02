#!/usr/bin/env bash
#
# verify.sh — build and test this project locally
#
# Stands in for CI: GitHub-hosted runners are unavailable on this account, so
# the check is provided as a script you can run yourself rather than as a
# workflow that would sit permanently red.
#
set -Eeuo pipefail

cd "$(dirname "$0")"

command -v mvn >/dev/null || { echo "maven is required" >&2; exit 1; }

echo "── building and testing ──"
mvn -B --no-transfer-progress verify

jar=$(find target -maxdepth 1 -name '*.jar' ! -name 'original-*' | head -1)
[[ -n "$jar" ]] || { echo "no jar produced" >&2; exit 1; }

echo
echo "artefact : $jar"
echo "size     : $(du -h "$jar" | cut -f1)"

# 65 is the class-file major version for Java 21.
major=$(python3 - "$jar" <<'PYEOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    entry = next(n for n in archive.namelist()
                 if n.endswith(".class") and "/libs/" not in n)
    print(int.from_bytes(archive.read(entry)[6:8], "big"))
PYEOF
)
echo "bytecode : major $major (65 = Java 21)"
[[ "$major" == "65" ]] || { echo "unexpected bytecode version" >&2; exit 1; }

echo
echo "verified"
