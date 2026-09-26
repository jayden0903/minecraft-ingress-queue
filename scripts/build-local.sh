#!/usr/bin/env bash
# Compile, test and package without Gradle, using jars you already have on disk.
#
#   JAVA_HOME        JDK 25 (javac/java/jar); falls back to PATH
#   VELOCITY_JAR     a Velocity proxy jar (the full server jar, not only the API):
#                    the proxy plugin touches Velocity internals (netty pipeline, packets)
#   PAPER_LIBRARIES  a directory scanned recursively for *.jar that contains paper-api and its
#                    dependencies, e.g. the libraries/ folder a Paper or Folia server creates on first start
#
# Parts whose inputs are missing are skipped with a notice. Output: build/local/ and build/libs/.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
bin() { if [[ -n "${JAVA_HOME:-}" ]]; then echo "$JAVA_HOME/bin/$1"; else echo "$1"; fi; }
JAVAC=$(bin javac); JAVA=$(bin java); JAR=$(bin jar)
OUT=build/local; LIBS=build/libs
rm -rf "$OUT"; mkdir -p "$OUT" "$LIBS"
JFLAGS=(-encoding UTF-8 -proc:none -Xlint:-options)
sources() { find "$1" -name '*.java' | sort; }
run_tests() { # classpath, test source dir
  local cp=$1 dir=$2
  for t in $(find "$dir" -name '*Test.java' | sort); do
    local cls=${t#"$dir"/}; cls=${cls%.java}; cls=${cls//\//.}
    echo "--- $cls"; "$JAVA" -ea --enable-native-access=ALL-UNNAMED -cp "$cp" "$cls"
  done
}

echo "== common (entry ticket + route balancer)"
"$JAVAC" --release 21 "${JFLAGS[@]}" -d "$OUT/common" $(sources common/src/main/java)
"$JAVAC" --release 21 "${JFLAGS[@]}" -cp "$OUT/common" -d "$OUT/common-test" $(sources common/src/test/java)
run_tests "$OUT/common:$OUT/common-test" common/src/test/java

if [[ -n "${VELOCITY_JAR:-}" ]]; then
  echo "== proxy (FairQueue for Velocity)"
  "$JAVAC" --release 21 "${JFLAGS[@]}" -cp "$VELOCITY_JAR:$OUT/common" -d "$OUT/proxy" $(sources proxy/src/main/java)
  "$JAR" cf "$LIBS/FairQueue.jar" -C "$OUT/proxy" . -C "$OUT/common" . -C proxy/src/main/resources .
  "$JAVAC" --release 21 "${JFLAGS[@]}" -cp "$VELOCITY_JAR:$OUT/common:$OUT/proxy" -d "$OUT/proxy-test" $(sources proxy/src/test/java)
  (cd "$OUT" && run_tests "$VELOCITY_JAR:common:proxy:proxy-test" ../../proxy/src/test/java)
else
  echo "== proxy skipped (set VELOCITY_JAR)"
fi

if [[ -n "${PAPER_LIBRARIES:-}" ]]; then
  PAPER_CP=$(find "$PAPER_LIBRARIES" -name '*.jar' | sort | paste -sd: -)
  echo "== router (IngressRouter for Paper/Folia)"
  "$JAVAC" --release 25 "${JFLAGS[@]}" -cp "$PAPER_CP:$OUT/common" -d "$OUT/router" $(sources router/src/main/java)
  "$JAR" cf "$LIBS/IngressRouter.jar" -C "$OUT/router" . -C "$OUT/common" . -C router/src/main/resources .
  echo "== lobby (FairQueueLobby for Paper)"
  "$JAVAC" --release 25 "${JFLAGS[@]}" -cp "$PAPER_CP" -d "$OUT/lobby" $(sources lobby/src/main/java)
  "$JAR" cf "$LIBS/FairQueueLobby.jar" -C "$OUT/lobby" . -C lobby/src/main/resources .
else
  echo "== router/lobby skipped (set PAPER_LIBRARIES)"
fi
ls -l "$LIBS"
