#!/bin/bash
# Spike S2: builds of the test app that differ from A by one edit each.
cd "$(dirname "$0")/../../testapks/composeapp" || exit 1
M=app/src/main/kotlin/dev/apksim/composeapp/MainActivity.kt
S=app/src/main/kotlin/dev/apksim/composeapp/SecondActivity.kt
OUT=../../work/hotswap
SP=$OUT/orig
mkdir -p $SP
cp $M $SP/M.kt; cp $S $SP/S.kt
b() {
  echo "== $1"; diff $SP/M.kt $M; diff $SP/S.kt $S
  ./gradlew -q :app:assembleDebug >/dev/null 2>&1 || echo "BUILD FAIL $1"
  cp app/build/outputs/apk/debug/app-debug.apk $OUT/$1.apk
  cp $SP/M.kt $M; cp $SP/S.kt $S
}
sed -i '' 's/label.text = "Stack \$depth: \$taps taps"/label.text = "Level $depth: $taps taps"/' $S; b B
sed -i '' '141s/FAB clicks: /FAB taps: /' $M; b C
sed -i '' '141s/item { Text("FAB clicks: \$count", Modifier.padding(8.dp)) }/item { Text("FAB clicks: $count", Modifier.padding(8.dp)); Text("Hot row") }/' $M; b D
sed -i '' '141s/$/\
              item { Text("New item") }/' $M; b E
sed -i '' '115s/count++/count += 2/' $M; b F
sed -i '' '141s/item { Text("FAB clicks: \$count", Modifier.padding(8.dp)) }/item { CountLabel(count) }/' $M
printf '\n@Composable\nprivate fun CountLabel(n: Int) { Text("Count: $n", Modifier.padding(8.dp)) }\n' >> $M; b G
cmp $SP/M.kt $M && cmp $SP/S.kt $S && echo restored
