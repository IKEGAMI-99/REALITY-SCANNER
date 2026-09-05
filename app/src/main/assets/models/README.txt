Place an exported YOLO26 detection model here as:

yolo26x.onnx

Recommended export target for the current detector:
- task: detect
- image size: 960
- input: NCHW float32 [1,3,H,W]
- output: Ultralytics end-to-end [1,N,6] or classic [1,84,N]

The app also checks:
files/models/yolo26x.onnx

This lets a future model manager replace the model without reinstalling the APK.
