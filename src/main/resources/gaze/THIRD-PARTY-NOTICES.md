# Third-party notices — webcam gaze tracking

The optional **Webcam eye tracker** input source bundles code and a model from
[**UnitEye**](https://github.com/wgnrto/uniteye) (Tobias Wagner et al., MuC '24), specifically
its standalone browser pipeline (`webgl/uniteye-core.js`, `webgl/uniteye-cv.js`,
`webgl/models/eyemu.onnx`), vendored unmodified under `gaze/web/`.

| File | Source | License |
|---|---|---|
| `web/uniteye-core.js` | UnitEye `webgl/uniteye-core.js` | GPL-3.0 |
| `web/uniteye-cv.js` | UnitEye `webgl/uniteye-cv.js` | GPL-3.0 |
| `web/models/eyemu.onnx` | UnitEye `webgl/models/eyemu.onnx` — the EyeMU gaze model | GPL-2.0 (EyeMU, [FIGLAB/EyeMU](https://github.com/FIGLAB/EyeMU)), redistributed by UnitEye under its GPL-3.0 project license |

The full GPL-3.0 text is included at `gaze/LICENSE-uniteye.txt`. Because these files are
distributed as part of this plugin, **this plugin's own source is effectively subject to
GPL-3.0 terms as a combined work** for as long as they're bundled — this is a research
prototype, not a commercial product, but anyone redistributing this plugin (or a derivative)
should keep that in mind.

At runtime, the bundled `uniteye-cv.js` additionally loads, over the network, from their own
CDNs (not vendored, not modified):

- [`onnxruntime-web@1.20.1`](https://www.npmjs.com/package/onnxruntime-web) (MIT) via jsDelivr
- [`@mediapipe/tasks-vision@1.0.0`](https://www.npmjs.com/package/@mediapipe/tasks-vision) (Apache-2.0) via jsDelivr, plus Google's `face_landmarker.task` model via `storage.googleapis.com`

This means webcam mode requires **internet access** on first use (and thereafter, unless the
embedded browser's HTTP cache retains them).
