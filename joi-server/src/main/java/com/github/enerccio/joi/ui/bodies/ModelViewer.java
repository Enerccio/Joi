package com.github.enerccio.joi.ui.bodies;

import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.google.gson.Gson;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.shared.Registration;

import java.io.File;
import java.util.List;

/**
 * The 3D preview of a model: three.js with three-vrm (the renderer's code, see {@code DESIGN.md} → Renderers) in the
 * {@code <joi-model-viewer>} element of {@code frontend/joi-model-viewer.js}. It shows a VRM, a glTF / GLB or an FBX,
 * alone or from a zip with its textures; the user orbits it with the mouse. It plays animation clips (VRMA) on a VRM.
 * The files are served to the browser of this session only (a {@link DownloadHandler} of the element).
 */
@Tag("joi-model-viewer")
@NpmPackage(value = "three", version = "0.187.0")
@NpmPackage(value = "@pixiv/three-vrm", version = "3.5.5")
@NpmPackage(value = "@pixiv/three-vrm-animation", version = "3.5.5")
@JsModule("./joi-model-viewer.js")
public class ModelViewer extends Component implements HasSize, HasStyle {

    /**
     * Shows the model.
     *
     * @param file     the uploaded file (the model, or the zip)
     * @param format   the format of the model
     * @param mainFile the path of the model in the zip, null when the file is the model
     */
    public void show(File file, ModelFormat format, String mainFile) {
        getElement().setAttribute("format", format.name());
        if (mainFile == null) {
            getElement().removeAttribute("main");
        } else {
            getElement().setAttribute("main", mainFile);
        }
        // the last: the element loads when src changes, a new handler is a new URL
        getElement().setAttribute("src", DownloadHandler.forFile(file, file.getName()).inline());
    }

    /** Sets the expression on the model shown (the others back to 0); weight 0 clears it. */
    public void setExpression(String name, double weight) {
        getElement().callJsFunction("setExpression", name, weight);
    }

    /** Shows or hides a mesh of the model shown. */
    public void setMeshVisible(String mesh, boolean visible) {
        getElement().callJsFunction("setMeshVisible", mesh, visible);
    }

    /** Sets a shape key of a mesh of the model shown. */
    public void setMorph(String mesh, String key, double weight) {
        getElement().callJsFunction("setMorph", mesh, key, weight);
    }

    /**
     * A toggle of the model.
     *
     * @param hides whether its shape key at full weight hides the part
     * @param shown whether to show it (where a state is set)
     */
    public record Toggle(String mesh, String key, boolean hides, Boolean shown) {
    }

    /**
     * A part to take close-ups of, one per state ({@code part-<index>-<state index>.jpg}).
     *
     * @param states for each state, whether each of the toggles is shown
     */
    public record Part(int index, List<Toggle> toggles, List<List<Boolean>> states) {
    }

    /** What the snapshots are taken in: the default state of every toggle, and the parts' states. */
    private record SnapshotRequest(List<Toggle> base, List<Part> parts) {
    }

    /** Sways the model from side to side (or stops), so its spring bones are seen moving. */
    public void setSway(boolean sway) {
        getElement().callJsFunction("setSway", sway);
    }

    /** Shows or hides toggles on the preview. */
    public void setToggles(List<Toggle> toggles) {
        getElement().callJsFunction("setToggles", new Gson().toJson(toggles));
    }

    /**
     * Takes the snapshots of the model shown, put in its default state (front, three-quarter, profile and back of the
     * full body, the face from the front and three-quarter, then a close-up of each part in each of its states) and sends
     * them to the handler, one file per shot named after it ({@code full-front.jpg}, {@code part-3-1.jpg}). The preview
     * is put back as it was. A {@link SnapshotsEvent} follows once the server has them all, a {@link SnapshotsErrorEvent}
     * when not.
     *
     * @param base the default state of every toggle
     */
    public void takeSnapshots(UploadHandler handler, List<Toggle> base, List<Part> parts) {
        getElement().setAttribute("snapshot-target", handler);
        getElement().callJsFunction("takeSnapshots", new Gson().toJson(new SnapshotRequest(base, parts)));
    }

    /**
     * Plays a clip (a VRMA) on the model shown, or on the next one once it is shown; a model shown later plays it again
     * until {@link #stopAnimation()}. An {@link AnimationStartedEvent} follows, or an {@link AnimationErrorEvent}.
     *
     * @param inPlace whether the hips keep their spot on the floor (a walk or a dance that moves about stays put)
     */
    public void playAnimation(File vrma, boolean loop, double speed, boolean inPlace) {
        getElement().setAttribute("animation-src", DownloadHandler.forFile(vrma, vrma.getName()).inline());
        getElement().callJsFunction("playAnimation", loop, speed, inPlace);
    }

    /** Whether the clip playing keeps its hips on their spot; it starts again. */
    public void setAnimationInPlace(boolean inPlace) {
        getElement().callJsFunction("setAnimationInPlace", inPlace);
    }

    /** Stops the clip and puts the model back at rest. */
    public void stopAnimation() {
        getElement().callJsFunction("stopAnimation");
        getElement().removeAttribute("animation-src");
    }

    /** Whether the clip playing loops. */
    public void setAnimationLoop(boolean loop) {
        getElement().callJsFunction("setAnimationLoop", loop);
    }

    /** How fast the clip plays (1 as made). */
    public void setAnimationSpeed(double speed) {
        getElement().callJsFunction("setAnimationSpeed", speed);
    }

    /** Pauses the clip where it is, or plays it on. */
    public void setAnimationPaused(boolean paused) {
        getElement().callJsFunction("setAnimationPaused", paused);
    }

    /** Shows nothing. */
    public void clear() {
        getElement().removeAttribute("src");
    }

    /** What the preview found in the model, once it is shown. */
    @DomEvent("model-info")
    public static class InfoEvent extends ComponentEvent<ModelViewer> {

        private final int triangles;
        private final int meshes;
        private final int materials;
        private final String vrm;
        private final int expressions;

        public InfoEvent(ModelViewer source, boolean fromClient,
                         @EventData("event.detail.triangles") int triangles,
                         @EventData("event.detail.meshes") int meshes,
                         @EventData("event.detail.materials") int materials,
                         @EventData("event.detail.vrm") String vrm,
                         @EventData("event.detail.expressions") int expressions) {
            super(source, fromClient);
            this.triangles = triangles;
            this.meshes = meshes;
            this.materials = materials;
            this.vrm = vrm;
            this.expressions = expressions;
        }

        public int getTriangles() {
            return triangles;
        }

        public int getMeshes() {
            return meshes;
        }

        public int getMaterials() {
            return materials;
        }

        /** The VRM version (0.x, 1.0), null when the model is not a VRM. */
        public String getVrm() {
            return vrm;
        }

        public int getExpressions() {
            return expressions;
        }
    }

    /** The preview could not show the model. */
    @DomEvent("model-error")
    public static class ErrorEvent extends ComponentEvent<ModelViewer> {

        private final String message;

        public ErrorEvent(ModelViewer source, boolean fromClient, @EventData("event.detail.message") String message) {
            super(source, fromClient);
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /** The server has the snapshots. */
    @DomEvent("snapshots-taken")
    public static class SnapshotsEvent extends ComponentEvent<ModelViewer> {

        private final int count;

        public SnapshotsEvent(ModelViewer source, boolean fromClient, @EventData("event.detail.count") int count) {
            super(source, fromClient);
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    /** The snapshots could not be taken or sent. */
    @DomEvent("snapshots-error")
    public static class SnapshotsErrorEvent extends ComponentEvent<ModelViewer> {

        private final String message;

        public SnapshotsErrorEvent(ModelViewer source, boolean fromClient, @EventData("event.detail.message") String message) {
            super(source, fromClient);
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /** A clip started playing. */
    @DomEvent("animation-started")
    public static class AnimationStartedEvent extends ComponentEvent<ModelViewer> {

        private final double duration;

        public AnimationStartedEvent(ModelViewer source, boolean fromClient, @EventData("event.detail.duration") double duration) {
            super(source, fromClient);
            this.duration = duration;
        }

        /** Its length in seconds. */
        public double getDuration() {
            return duration;
        }
    }

    /** A clip played once came to its end. */
    @DomEvent("animation-finished")
    public static class AnimationFinishedEvent extends ComponentEvent<ModelViewer> {

        public AnimationFinishedEvent(ModelViewer source, boolean fromClient) {
            super(source, fromClient);
        }
    }

    /** A clip could not be played. */
    @DomEvent("animation-error")
    public static class AnimationErrorEvent extends ComponentEvent<ModelViewer> {

        private final String message;

        public AnimationErrorEvent(ModelViewer source, boolean fromClient, @EventData("event.detail.message") String message) {
            super(source, fromClient);
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    public Registration addAnimationStartedListener(com.vaadin.flow.component.ComponentEventListener<AnimationStartedEvent> listener) {
        return addListener(AnimationStartedEvent.class, listener);
    }

    public Registration addAnimationFinishedListener(com.vaadin.flow.component.ComponentEventListener<AnimationFinishedEvent> listener) {
        return addListener(AnimationFinishedEvent.class, listener);
    }

    public Registration addAnimationErrorListener(com.vaadin.flow.component.ComponentEventListener<AnimationErrorEvent> listener) {
        return addListener(AnimationErrorEvent.class, listener);
    }

    public Registration addSnapshotsListener(com.vaadin.flow.component.ComponentEventListener<SnapshotsEvent> listener) {
        return addListener(SnapshotsEvent.class, listener);
    }

    public Registration addSnapshotsErrorListener(com.vaadin.flow.component.ComponentEventListener<SnapshotsErrorEvent> listener) {
        return addListener(SnapshotsErrorEvent.class, listener);
    }

    public Registration addInfoListener(com.vaadin.flow.component.ComponentEventListener<InfoEvent> listener) {
        return addListener(InfoEvent.class, listener);
    }

    public Registration addErrorListener(com.vaadin.flow.component.ComponentEventListener<ErrorEvent> listener) {
        return addListener(ErrorEvent.class, listener);
    }
}
