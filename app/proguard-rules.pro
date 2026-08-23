# Let R8 trace actual call sites so unused helpers are removed safely.
# MainActivity is referenced by the manifest; the processing classes are referenced
# directly by code and do not need broad keep rules.
-keep class com.vr3th.mediacompressor.MainActivity { *; }
