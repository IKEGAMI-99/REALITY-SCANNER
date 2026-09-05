from ultralytics import YOLO

# High-accuracy local model. Export on a desktop/CI runner, then copy the resulting
# yolo26x.onnx to app/src/main/assets/models/yolo26x.onnx.
model = YOLO("yolo26x.pt")
model.export(
    format="onnx",
    imgsz=960,
    simplify=True,
    dynamic=False,
    nms=True,
)
