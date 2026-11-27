---
label: The UI
icon: browser
order: 700
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/ui
  - joi-server/src/main/java/com/github/enerccio/joi/loc
verified: 49e75e9
---

# The UI

The UI is Vaadin Flow in Java, calling services directly: there is no REST API behind it.

## The workspace

After the login `ui/workspace/JoiWorkspace` shows tabs of `WorkspacePart`s, with their state kept by `WorkspaceAutosave`. The parts are in
`ui/workspace/parts`: `JoisPart`, `BodiesPart`, `AnimationsPart` (Clips), `AnimationPacksPart` (Animations), `AIPart` (Models),
`TrashPart`, `UserSettingsPart` and `AdminPart` (tabs: users, GPUs, app settings (`ui/settings/AppSettingsPanel`: `JoiAppSettings` in the platform's `AppSettings` under `joi_app`, through `JoiAppSettingsService`; local servers and the workspace limits), API access, tools, extensions (`ui/extensions/ExtensionsPanel`), update (the platform's `UpdatePanel` and `UpdateService`: the application asks by writing `data/update/request`, the watcher `tools/update-watcher/watch.sh` runs `rebuild.sh` and writes `status.properties`; the commit of the build is `git.properties` from the git-commit-id plugin of `joi-server/pom.xml`), jobs (the platform's
`JobsPanel`), database backups, cleanup, resource files). The tabs the loaded extensions add come after the settings (`ExtensionTab`, see
[Extensions](extensions.md)). `/jobs` is `ui/main/JoiJobsView` with `ui/jobs/AssistantsRunsGrid` (see [Jobs](jobs.md)).

The assistant's dialog is `ui/dialogs/JoiDialog`, with a `JoiDialogPart` for each tab: `JoiAboutPart`, `JoiBodiesPart`,
`JoiAnimationsPart`, `JoiProfilesPart` (her profiles and the nodes assigned to them, see *Profiles* in [Bodies and models](bodies.md)), `JoiTemplatesPart`, `JoiMemoriesPart`, `JoiChatsPart` (with the Summarize button), `JoiSummariesPart`, `JoiTasksPart` and `JoiFilesPart` (her workspace in the platform's `WorkspaceBrowser`). `JoiBodiesPart` shows a Profile choice when she has more than the default profile: the body made active and the
`BodyDialog` it opens (with the profile) change that profile; `profilesChanged()` follows the Profiles tab. A part stores
its change with `JoiDialog.save(change)` (or `saveAbout(before, change)`), which applies it to her as stored now: her turns save her
mood while the dialog is open. The About tab's Mood today box is no part of the persona: it calls `ChatService.changeMood(joi, key, true)`. The settings of the
summaries are one widget, `ui/widgets/SummaryFields`, shared by the assistant's About tab and the user's settings (the chain assistant → user → default). Other dialogs: `ModelDialog`, `BodyDialog`,
`OutfitDialog`, `AnimationDialog`, `AnimationPackDialog`, `TtsModelDialog`, `JoiAIDialog`, `PersonalityDialog`, `MoodDialog` (with the mood's face: expression, strength and eyes).
Panels: `ui/bodies` (`AppearancePanel`, `SpringBonesPanel`, `ToggleGroupsPanel`, `ModelViewer`, `ClipPreview`), `ui/tools`, `ui/access` (its list shows a `LOCAL` token as "This computer" and the administration scopes of a token in *Access*; *Create token* offers the scopes (`AdminScope`) only to an administrator; *Pair a phone* is `PairPhoneDialog`: a QR code (ZXing, a data-URI PNG) of `joi://pair?server=...&offer=...`, the server address editable because a phone cannot reach `localhost`, and a poll of the UI every 1.5 s that approves the device that came with the offer, as the user of the screen: `DeviceAuthorizationService.waitingForOffer` and `approve`).

## Rules

- UI classes are created with `new` and are `@Configurable`, with compile-time AspectJ weaving. Plain `javac` leaves the injected fields
  `null`.
- UI changes from other threads go through `ui.access(...)`. A refresh from another thread that calls services (they find the user in the session) runs in a copy of the page's request: `ThreadCopyRequestAttributes.create()` on the page's thread when the watch starts, then `InRequestScope` inside `ui.access`, as the Summaries and Tasks tabs of the Joi dialog do.
- Texts are in the localization enum `loc/J_L` with `JoiLocalizationBundleEN`: every key has an English text. Never put a user-visible
  string in a class. The platform's own texts (`L`) say Joi where they name Marginalia through `loc/JoiLocalizationOverride`, a
  `LocalizationOverride` bean of the platform (a replacement for every language, a few texts of Joi's own); a text of the platform changes there,
  not in a subclass of its bundles.
- The login: the dialog's footer and the version Joi shows are `BuildInfo` of Joi (`JoiApplicationInitializer` installs it, the version is
  `META-INF/build-info/build-info.properties`); the saved-login cookies start with `joi` (`joi.cookie-namespace`) and the session cookie is
  `JOISESSIONID`, so Joi and Marginalia on one host do not log each other out.
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

## The chat input

`JoiChatsPart` (the Chats tab) has the compose row: a text area (Enter sends: `Shortcuts`; its value change mode is `EAGER`, or the Enter would send the value of the last blur and lose the words), a `Upload` as the attach button (`image/*`, at most 4, 20 MB, `UploadHandler.toFile`), paste and drop
(`JoiJavascriptLibrary.takeImages` hands the files of a paste or drop to the `vaadin-upload`), the waiting images as thumbnails with a remove button, and Send. `send()` checks the images
(`ChatService.checkImages`: a `no_vision` is the notification `MSG_CHAT_NO_VISION`, nothing is lost), then runs the turn in a virtual thread with `ApiRequestAttributes` and `InRequestScope`, as
the API's chat does; the tab shows the waiting message when the turn starts and the exchange when it is stored. Attach is disabled when `ChatImageService.canSee` is false. The cards show the
images as `Anchor` + `Image` on a `StreamResource` of the stored file (`ChatImageService.file`); the styles are `.chat-images`, `.chat-image`, `.chat-compose*`.

## Prompts

Every prompt sent to a model is a template, so the user can change it: a `JoiPromptField` (fields on `Joi`, `JoiUserSetting` and
`PersonaAttributes`, the default in `JoiDefaults`, its variables in a `*TemplateData` with `@JoiTemplateDescription`, a label in `PromptAreas`),
shown in both Templates tabs and rendered with `PersonaService.render(joi, field, data)`. Never build a prompt from hard-coded strings in a
service. Template data extends `JoiTemplateData` (`{{char}}`, `{{user}}`); do not name a variable after a SillyTavern macro (`personality`,
`persona`, `description`, `scenario`...): the platform swallows it.
