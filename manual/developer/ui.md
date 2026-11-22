---
label: The UI
icon: browser
order: 700
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/ui
  - joi-server/src/main/java/com/github/enerccio/joi/loc
verified: 714077a
---

# The UI

The UI is Vaadin Flow in Java, calling services directly: there is no REST API behind it.

## The workspace

After the login `ui/workspace/JoiWorkspace` shows tabs of `WorkspacePart`s, with their state kept by `WorkspaceAutosave`. The parts are in
`ui/workspace/parts`: `JoisPart`, `BodiesPart`, `AnimationsPart` (Clips), `AnimationPacksPart` (Animations), `AIPart` (Models),
`TrashPart`, `UserSettingsPart` and `AdminPart` (tabs: users, GPUs, app settings (`ui/settings/AppSettingsPanel`: `JoiAppSettings` in the platform's `AppSettings` under `joi_app`, through `JoiAppSettingsService`), API access, tools, extensions (`ui/extensions/ExtensionsPanel`), jobs (the platform's
`JobsPanel`), database backups, cleanup, resource files). The tabs the loaded extensions add come after the settings (`ExtensionTab`, see
[Extensions](extensions.md)). `/jobs` is `ui/main/JoiJobsView` with `ui/jobs/AssistantsRunsGrid` (see [Jobs](jobs.md)).

The assistant's dialog is `ui/dialogs/JoiDialog`, with a `JoiDialogPart` for each tab: `JoiAboutPart`, `JoiBodiesPart`,
`JoiAnimationsPart`, `JoiTemplatesPart`, `JoiMemoriesPart`, `JoiChatsPart` (with the Summarize button), `JoiSummariesPart` and `JoiTasksPart`. A part stores
its change with `JoiDialog.save(change)` (or `saveAbout(before, change)`), which applies it to her as stored now: her turns save her
mood while the dialog is open. The settings of the
summaries are one widget, `ui/widgets/SummaryFields`, shared by the assistant's About tab and the user's settings (the chain assistant → user → default). Other dialogs: `ModelDialog`, `BodyDialog`,
`OutfitDialog`, `AnimationDialog`, `AnimationPackDialog`, `TtsModelDialog`, `JoiAIDialog`, `PersonalityDialog`, `MoodDialog`.
Panels: `ui/bodies` (`AppearancePanel`, `SpringBonesPanel`, `ToggleGroupsPanel`, `ModelViewer`, `ClipPreview`), `ui/tools`, `ui/access` (its list shows a `LOCAL` token as "This computer"; *Pair a phone* is `PairPhoneDialog`: a QR code (ZXing, a data-URI PNG) of `joi://pair?server=...&offer=...`, the server address editable because a phone cannot reach `localhost`, and a poll of the UI every 1.5 s that approves the device that came with the offer, as the user of the screen: `DeviceAuthorizationService.waitingForOffer` and `approve`).

## Rules

- UI classes are created with `new` and are `@Configurable`, with compile-time AspectJ weaving. Plain `javac` leaves the injected fields
  `null`.
- UI changes from other threads go through `ui.access(...)`.
- Texts are in the localization enum `loc/J_L` with `JoiLocalizationBundleEN`: every key has an English text. Never put a user-visible
  string in a class.
- Styles are CSS classes: `frontend/styles/joi-styles.css` with `JoiCssStyles`. No inline styles.
- Scripts are in one JavaScript library class, `ui/JoiJavascriptLibrary`.
- Dialogs have focus and keyboard submit. Text areas for prompts expand (`ui/widgets/PromptAreas`, the platform's `ResizableTextArea`).
- The model and body dialogs split the editing side and a 3D preview with `ui/widgets/EditorSplit`; where the divider is left is kept in the
  browser per dialog.

## The viewer

`frontend/joi-model-viewer.js` is the `<joi-model-viewer>` element: three.js and `@pixiv/three-vrm`, with orbit controls, expressions,
toggles, animations (`playSequence`), snapshots (`takeSnapshots`) and the spring-bone preview. The node's renderer page uses the same
file. The animation dialog also sets the face an animation forces (`Eyes`, `Expression`; its preview passes them with the sequence: `ClipPreview.setFace`, `ModelViewer.setAnimationFace`), and the
viewer's face layer (`setFace`) and mouth (`setMouth`) are off until a page calls them: see [Writing a node](nodes.md).

## Prompts

Every prompt sent to a model is a template, so the user can change it: a `JoiPromptField` (fields on `Joi`, `JoiUserSetting` and
`PersonaAttributes`, the default in `JoiDefaults`, its variables in a `*TemplateData` with `@JoiTemplateDescription`, a label in `PromptAreas`),
shown in both Templates tabs and rendered with `PersonaService.render(joi, field, data)`. Never build a prompt from hard-coded strings in a
service. Template data extends `JoiTemplateData` (`{{char}}`, `{{user}}`); do not name a variable after a SillyTavern macro (`personality`,
`persona`, `description`, `scenario`...): the platform swallows it.
