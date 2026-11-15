# ONNX Runtime's native library creates these classes, calls their constructors and reads their
# fields by name (tensors, type information, exceptions).
-keep class ai.onnxruntime.** { *; }
