# Keep the fixture's methods whole (not inlined into their callers, arguments not folded) but let R8 optimize their
# bodies and allocate registers as in a release app: register reuse across types is what the checks need.
-keep class fixture.** { *; }
-dontobfuscate
