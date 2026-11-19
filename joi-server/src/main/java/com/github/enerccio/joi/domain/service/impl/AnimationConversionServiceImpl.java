package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.Blender;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.service.AnimationConversionService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.bound.ApiRequestAttributes;
import com.github.enerccio.marginalia.bound.SessionPoint;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.security.service.UserService;
import com.github.enerccio.marginalia.ui.components.ThreadCopyRequestAttributes.InRequestScope;
import com.google.gson.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnimationConversionServiceImpl implements AnimationConversionService, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnimationConversionServiceImpl.class);

    /** The converter, a resource next to the package of the clips. */
    static final String SCRIPT = "/com/github/enerccio/joi/domain/animations/convert_animation.py";
    private static final long TIMEOUT_MINUTES = 15;
    private static final int LOG_LINES = 60;

    @Autowired
    private AnimationService animationService;

    @Autowired
    private ModelService modelService;

    @Autowired
    private UserService userService;

    @Autowired
    private User currentUser;

    @Autowired
    private Configuration configuration;

    private String blender;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "animation-conversion");
        thread.setDaemon(true);
        return thread;
    });

    /** The Blender executable ({@code joi.blender}); blank for the default. */
    public void setBlender(String blender) {
        this.blender = blender;
    }

    @Override
    public void convert(File upload, String fileName, Listener listener) throws Exception {
        File folder = modelService.uploadFolder().getCanonicalFile();
        if (!folder.equals(upload.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("not an upload file: " + upload);
        }
        if (!AnimationConversionService.isConvertible(fileName)) {
            Files.deleteIfExists(upload.toPath());
            throw new IllegalArgumentException("not an FBX or a BVH: " + fileName);
        }
        User user = userService.find(currentUser.getId());
        executor.submit(() -> {
            List<Animation> added = new ArrayList<>();
            String error = null;
            try (ApiRequestAttributes attributes = new ApiRequestAttributes(user, new SessionPoint());
                 InRequestScope _ = new InRequestScope(attributes)) {
                try {
                    added.addAll(run(upload, fileName));
                } catch (Exception e) {
                    error = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                    log.warn("The conversion of the clip {} failed: {}", fileName, error, e);
                }
                if (listener != null) {
                    try {
                        // in the request scope of the user: ui.access may run the listener in this thread
                        listener.finished(added, error);
                    } catch (Exception e) {
                        log.debug("A listener of the clip conversion failed", e);
                    }
                }
            } catch (Throwable t) {
                log.error("The conversion of the clip {} could not run", fileName, t);
            } finally {
                FileUtils.deleteQuietly(upload);
            }
        });
    }

    /** The conversion, in the thread of the conversions, as the user: the clips it added. */
    private List<Animation> run(File upload, String fileName) throws Exception {
        Path work = Files.createDirectories(configuration.getDataFolder().toPath().resolve("work").resolve("animations"))
                .resolve(currentUser.getId() + "-" + System.currentTimeMillis());
        Deque<String> tail = new ArrayDeque<>();
        try {
            Path input = Files.createDirectories(work.resolve("src"))
                    .resolve("clip." + FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT));
            Files.copy(upload.toPath(), input, StandardCopyOption.REPLACE_EXISTING);
            Path output = work.resolve("out");
            Path reportFile = work.resolve("report.json");
            Path script = Blender.script(getClass(), SCRIPT, work);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("input", input.toString());
            config.put("outputDir", output.toString());
            config.put("report", reportFile.toString());
            Path configFile = work.resolve("config.json");
            Files.writeString(configFile, new Gson().toJson(config), StandardCharsets.UTF_8);

            int exit = Blender.run(Blender.executable(blender), script, configFile, work, line -> {
                tail.addLast(line);
                while (tail.size() > LOG_LINES) {
                    tail.removeFirst();
                }
            }, TIMEOUT_MINUTES);
            JsonObject report = Files.isRegularFile(reportFile)
                    ? JsonParser.parseString(Files.readString(reportFile, StandardCharsets.UTF_8)).getAsJsonObject() : null;
            if (exit != 0 || report == null) {
                String reported = report != null && report.has("error") ? report.get("error").getAsString() : null;
                if (reported == null) {
                    log.warn("Blender's output for the clip {}:\n{}", fileName, String.join("\n", tail));
                }
                throw new IllegalStateException(reported != null ? reported : "Blender ended with " + exit);
            }
            JsonArray clips = report.has("clips") ? report.getAsJsonArray("clips") : new JsonArray();
            if (clips.isEmpty()) {
                throw new IllegalStateException("the file has no animation");
            }
            String base = FilenameUtils.getBaseName(fileName);
            List<Animation> added = new ArrayList<>();
            for (JsonElement element : clips) {
                JsonObject clip = element.getAsJsonObject();
                Path vrma = output.resolve(clip.get("file").getAsString());
                String take = clip.has("take") ? StringUtils.defaultIfBlank(clip.get("take").getAsString(), null) : null;
                String name = clips.size() == 1 || take == null ? base : base + " – " + take;
                File stored = ModelFiles.uploadFile(modelService.uploadFolder(), "clip.vrma");
                Files.move(vrma, stored.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Animation animation = animationService.add(stored, name + ".vrma");
                animation.setSource(fileName);
                animation.setTake(take);
                added.add(animationService.save(animation));
            }
            return added;
        } finally {
            FileUtils.deleteQuietly(work.toFile());
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
