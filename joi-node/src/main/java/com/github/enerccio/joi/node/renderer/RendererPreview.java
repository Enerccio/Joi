package com.github.enerccio.joi.node.renderer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The renderer page with a body from files, without a server: for development. Prints the page's address; reads
 * {@code play <animation uuid> [loop]} and {@code stop} from the standard input.
 * <p>
 * {@code java ... RendererPreview <her .joim> <her .joia> <a folder to unpack into>}
 */
public final class RendererPreview {

    public static void main(String[] args) throws Exception {
        try (RendererServer server = new RendererServer()) {
            server.setOnEnded((id, problem) -> System.out.println("ended " + id + (problem == null ? "" : ": " + problem)));
            BodyFiles body = BodyFiles.unpack(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            body.animations().forEach(animation -> System.out.println(animation.uuid() + " " + animation.name()));
            server.show(body);
            System.out.println(server.url());
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                String[] words = line.strip().split("\\s+");
                if (words[0].equals("play") && words.length > 1) {
                    System.out.println("playing " + server.play(words[1], words.length > 2 && words[2].equals("loop")));
                } else if (words[0].equals("stop")) {
                    server.play(null, true);
                }
            }
        }
    }
}
