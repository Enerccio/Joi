package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.ConversionSource;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBones;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelConversionService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ModelConversionServiceImpl implements ModelConversionService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModelConversionServiceImpl.class);

    /** The converter, a resource next to this class's package. */
    static final String SCRIPT = "/com/github/enerccio/joi/domain/bodies/convert_avatar.py";
    private static final String MAC_BLENDER = "/Applications/Blender.app/Contents/MacOS/Blender";
    private static final int LOG_LINES = 400;
    private static final long TIMEOUT_MINUTES = 60;

    @Autowired
    private ModelService modelService;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    @Autowired
    private Configuration configuration;

    private String blender;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "model-conversion");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<Long, List<Listener>> listeners = new ConcurrentHashMap<>();

    /** The Blender executable ({@code joi.blender}); blank for the default. */
    public void setBlender(String blender) {
        this.blender = blender;
    }

    /** The Blender to run: the configured one, else Blender.app on macOS, else the one on the PATH. */
    String blenderExecutable() {
        if (StringUtils.isNotBlank(blender)) {
            return blender.strip();
        }
        return new File(MAC_BLENDER).canExecute() ? MAC_BLENDER : "blender";
    }

    @Override
    public void convert(Model model, Listener listener) throws Exception {
        Model own = modelService.find(model.getId());
        if (own == null || own.getOwner() == null || !own.getOwner().getId().equals(currentUser.getId())) {
            throw new IllegalArgumentException("not a model of the user");
        }
        if (own.fbxToConvert() == null) {
            throw new IllegalStateException(Boolean.TRUE.equals(own.getUploadRemoved())
                    ? "the upload was removed: upload it again to convert" : "the model has no FBX to convert");
        }
        if (!running.add(own.getId())) {
            throw new IllegalStateException("the model is converting already");
        }
        List<Listener> watching = listeners.computeIfAbsent(own.getId(), id -> new CopyOnWriteArrayList<>());
        if (listener != null) {
            watching.add(listener);
        }
        try {
            own.setConversionStatus(ConversionStatus.RUNNING);
            own.setConversionError(null);
            modelService.save(own);
        } catch (Exception e) {
            running.remove(own.getId());
            throw e;
        }
        User user = userService.find(currentUser.getId());
        Long id = own.getId();
        executor.submit(() -> {
            try (ApiRequestAttributes attributes = new ApiRequestAttributes(user, new SessionPoint());
                 InRequestScope _ = new InRequestScope(attributes)) {
                Model finished = null;
                try {
                    finished = run(id);
                } catch (Throwable t) {
                    log.error("The conversion of model {} failed", id, t);
                } finally {
                    // no longer running before the watchers hear of the end (they ask isRunning); they hear of it in
                    // the request scope of the user, since ui.access may run them right in this thread
                    running.remove(id);
                    List<Listener> watched = listeners.remove(id);
                    if (finished != null && watched != null) {
                        for (Listener watcher : watched) {
                            try {
                                watcher.finished(finished);
                            } catch (Exception e) {
                                log.debug("A listener of the conversion failed", e);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                running.remove(id);
                listeners.remove(id);
                log.error("The conversion of model {} could not start", id, t);
            }
        });
    }

    @Override
    public boolean isRunning(Model model) {
        return model.getId() != null && running.contains(model.getId());
    }

    @Override
    public Runnable listen(Model model, Listener listener) {
        if (!isRunning(model)) {
            return () -> {
            };
        }
        List<Listener> watching = listeners.computeIfAbsent(model.getId(), id -> new CopyOnWriteArrayList<>());
        watching.add(listener);
        return () -> watching.remove(listener);
    }

    /** The conversion, in the thread of the conversions, as the user. */
    private Model run(Long id) throws Exception {
        Deque<String> tail = new ArrayDeque<>();
        Path work = Files.createDirectories(configuration.getDataFolder().toPath().resolve("work").resolve("conversions"))
                .resolve(id + "-" + System.currentTimeMillis());
        String error = null;
        String report = null;
        File vrm = null;
        List<SpringChain> springChains = null;
        int springCount = 0;
        try {
            Model model = modelService.find(id);
            File upload = modelService.file(model);
            if (upload == null) {
                throw new IllegalStateException("the uploaded file is gone");
            }
            emit(id, tail, "Unpacking " + model.getFileName() + "…");
            ConversionSource source = ConversionSource.unpack(upload, model.getFileName(), work.resolve("src"));
            String fbx = model.fbxToConvert();
            // a single FBX is unpacked under its own name, which is its only entry of fbxFiles
            String fbxPath = fbx;
            if (!Files.isRegularFile(source.root().resolve(fbxPath))) {
                throw new IllegalStateException("the FBX " + fbx + " is not in the upload");
            }
            Map<String, Map<String, Object>> materials = source.materials();
            Path materialsJson = work.resolve("materials.json");
            Files.writeString(materialsJson, gson.toJson(materials), StandardCharsets.UTF_8);
            emit(id, tail, "Found " + materials.size() + " Unity materials.");

            Path output = work.resolve("out.vrm");
            Path reportFile = work.resolve("report.json");
            Path script = work.resolve("convert_avatar.py");
            try (InputStream in = getClass().getResourceAsStream(SCRIPT)) {
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            Path config = work.resolve("config.json");
            Files.writeString(config, gson.toJson(config(model, source, fbxPath, materialsJson, output, reportFile)), StandardCharsets.UTF_8);

            String executable = blenderExecutable();
            emit(id, tail, "Running " + executable + "…");
            Process process;
            try {
                process = new ProcessBuilder(executable, "-b", "--factory-startup", "--python-exit-code", "1",
                        "--python", script.toString(), "--", config.toString())
                        .redirectErrorStream(true).directory(work.toFile()).start();
            } catch (IOException e) {
                throw new IllegalStateException("Blender was not found (" + executable + "): install it with the VRM add-on, "
                        + "or set JOI_BLENDER to its executable", e);
            }
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    emit(id, tail, line);
                }
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Blender did not finish in " + TIMEOUT_MINUTES + " minutes");
            }
            if (Files.isRegularFile(reportFile)) {
                report = Files.readString(reportFile, StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                String reported = reportedError(report);
                throw new IllegalStateException(reported != null ? reported : "Blender ended with " + process.exitValue());
            }
            vrm = ModelFiles.uploadFile(modelService.uploadFolder(), "model.vrm");
            // the spring bones: the model's chains, guessed from the bones' names at its first conversion
            try {
                SpringBones.Result springs = SpringBones.write(output.toFile(), vrm,
                        SpringChain.listFromJson(modelService.find(id).getSpringChains()));
                springChains = springs.chains();
                springCount = springs.springs();
                emit(id, tail, "Spring bones: " + springs.chains().stream().filter(SpringChain::enabled).count() + " chains, "
                        + springs.springs() + " springs.");
                Files.deleteIfExists(output);
            } catch (IOException e) {
                emit(id, tail, "Spring bones were not written: " + e.getMessage());
                Files.move(output, vrm.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            error = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            emit(id, tail, "FAILED: " + error);
            log.warn("The conversion of model {} failed: {}", id, error, e);
        } finally {
            FileUtils.deleteQuietly(work.toFile());
        }

        Model model = modelService.find(id);
        if (vrm != null) {
            try {
                modelService.attachConverted(model, vrm);
            } catch (Exception e) {
                error = "the VRM could not be stored: " + e.getMessage();
                FileUtils.deleteQuietly(vrm);
            }
        }
        model.setConversionStatus(error == null ? ConversionStatus.DONE : ConversionStatus.FAILED);
        model.setConversionError(error);
        if (springChains != null && error == null) {
            String chains = SpringChain.toJson(springChains);
            model.setSpringChains(chains);
            model.setAppliedSpringChains(chains);
            report = withSprings(report, springCount);
        }
        model.setConversionReport(report);
        model.setConversionLog(String.join("\n", tail));
        return modelService.save(model);
    }

    /** The converter's config for the model (see the script's documentation). */
    Map<String, Object> config(Model model, ConversionSource source, String fbx, Path materialsJson, Path output, Path report)
            throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("fbx", source.root().resolve(fbx).toString());
        config.put("output", output.toString());
        config.put("report", report.toString());
        config.put("name", StringUtils.defaultIfBlank(model.getName(), "avatar"));
        Path meta = source.root().resolve(fbx + ".meta");
        if (Files.isRegularFile(meta)) {
            config.put("unityMeta", meta.toString());
        }
        config.put("materialsJson", materialsJson.toString());
        config.put("textureDir", source.root().toString());
        config.put("materialMap", source.materialMap(fbx));
        config.put("maxTextureSize", model.getMaxTextureSize() == null ? 2048 : model.getMaxTextureSize());
        config.put("removeMeshes", model.getRemoveMeshes());
        config.put("dropShapeKeyParts", model.getDropShapeKeys());
        Map<String, List<String>> bake = new LinkedHashMap<>();
        for (String pair : model.getBakeShapeKeys()) {
            int slash = pair.indexOf(" / ");
            if (slash > 0) {
                bake.computeIfAbsent(pair.substring(0, slash).strip(), k -> new ArrayList<>()).add(pair.substring(slash + 3).strip());
            }
        }
        config.put("bakeShapeKeys", bake);
        return config;
    }

    /** The report with the number of springs written (the converter leaves them to Joi). */
    private String withSprings(String report, int springs) {
        if (report == null) {
            return null;
        }
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(report).getAsJsonObject();
            json.addProperty("springs", springs);
            return gson.toJson(json);
        } catch (Exception e) {
            return report;
        }
    }

    private String reportedError(String report) {
        if (report == null) {
            return null;
        }
        try {
            Map<?, ?> map = gson.fromJson(report, Map.class);
            Object error = map == null ? null : map.get("error");
            return error == null ? null : error.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void emit(Long id, Deque<String> tail, String line) {
        tail.addLast(line);
        while (tail.size() > LOG_LINES) {
            tail.removeFirst();
        }
        for (Listener listener : listeners.getOrDefault(id, List.of())) {
            try {
                listener.line(line);
            } catch (Exception e) {
                log.debug("A listener of the conversion failed", e);
            }
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
