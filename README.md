# GazeAIDE — PyCharm Plugin

> **Prototype** — This plugin was AI-generated and may contain errors or incomplete functionality. It is a proof-of-concept prototype developed as part of the **Implicit** project.

GazeAIDE simulates eye-tracking gaze behaviour using the mouse cursor inside PyCharm (and any JetBrains IDE). It is inspired by the [CodeGRITS](https://github.com/codegrits/CodeGRITS) research toolkit.

## How it works

| Mode | Trigger | What happens |
|------|---------|--------------|
| **Reading** | Mouse is moving | A circle appears in the gutter next to the current line |
| **Explaining** | Mouse rests on a line for 3 seconds | The line is highlighted and GPT-4o-mini explains it inline, using the surrounding function as context |

The current mode is shown in the status bar as `● GAZEAIDE: Reading` or `● GAZEAIDE: Explaining`. Clicking it opens a reading-coverage report.

---

## Requirements

- **PyCharm** 2023.1 or later (Community or Professional) — also works in other JetBrains IDEs
- **Java 17** — PyCharm ships a bundled JDK; see [Using the bundled JDK](#using-the-bundled-jdk) below
- **Gradle 8.6** — fetched automatically by `setup.sh`
- **OpenAI API key** — required only for the AI explanation feature

---

## Installation

### 1. Clone the repository

```bash
git clone https://github.com/heidialbarazi/gazeaide.git
cd gazeaide
```

### 2. Set your OpenAI API key

The plugin reads the key from the environment variable `OPENAI_API_KEY`.

```bash
export OPENAI_API_KEY="sk-..."
```

To make this permanent, add the line above to your `~/.zshrc` or `~/.bash_profile`.

### 3. Bootstrap Gradle

Run the provided setup script once to download Gradle and generate the wrapper:

```bash
chmod +x setup.sh
./setup.sh
```

The script automatically detects PyCharm's bundled JDK. If it cannot find it, set `JAVA_HOME` manually first (see [Using the bundled JDK](#using-the-bundled-jdk)).

### 4. Build the plugin

```bash
./gradlew buildPlugin
```

This produces a distributable ZIP at:

```
build/distributions/gaze-highlighter-0.1.0.zip
```

### 5. Install in PyCharm

1. Open PyCharm → **Settings / Preferences** → **Plugins**
2. Click the gear icon ⚙ → **Install Plugin from Disk…**
3. Select `build/distributions/gaze-highlighter-0.1.0.zip`
4. Restart PyCharm when prompted

---

## Using the bundled JDK

If `setup.sh` cannot locate Java automatically, point it to PyCharm's bundled JDK:

```bash
# macOS (typical path — adjust version numbers as needed)
export JAVA_HOME="$HOME/Library/Application Support/JetBrains/Toolbox/apps/PyCharm-P/ch-0/<version>/PyCharm.app/Contents/jbr"

# Then run setup and build
./setup.sh
./gradlew buildPlugin
```

You can also find the exact path inside PyCharm via **Help → Find Action → "Choose Boot Java Runtime for the IDE"**.

---

## Project structure

```
src/main/java/io/github/gazehighlighter/
├── GazeHighlighterStartupActivity.java  — attaches mouse listeners on IDE start
├── GazeModeService.java                 — project-scoped service; owns current mode
├── GazeMouseListener.java               — transitions between Reading / Explaining
├── GazeMode.java                        — enum: READING, EXPLAINING
├── GazeStatusBarWidget.java             — status bar indicator + click handler
├── GazeStatusBarWidgetFactory.java
├── CoverageTracker.java                 — tracks which lines were "read"
├── FunctionExtractor.java               — extracts surrounding function for context
├── LlmExplainer.java                    — calls GPT-4o-mini via OpenAI API
├── ExplanationRenderer.java             — renders inline explanation text
├── GutterCircleRenderer.java            — draws the gaze-circle in the gutter
└── ReadingReportDialog.java             — coverage summary dialog
```

---

## Disclaimer

This plugin is a **prototype** built as part of the **Implicit** research project. It was AI-generated and has not been thoroughly tested. Expect rough edges, potential crashes, and incomplete features. Use it for exploration and research purposes only.
