from ultralytics import YOLO

# Compatibility fallback. NMS is intentionally kept outside the graph because the app already
# performs NMS in Kotlin and the embedded post-processing graph is harder for Android delegates.
model = YOLO("yolo26x.pt")
model.export(
    format="onnx",
    imgsz=960,
    simplify=True,
    dynamic=False,
    nms=False,
)
