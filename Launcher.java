import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.*;

public class Launcher {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Path ROOT = Paths.get(System.getProperty("user.home"), ".mylauncher");
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String RUNTIMES = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    static final String OS = osName();
    static JTextArea logArea;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Launcher::gui);
    }

    static void gui() {
        JFrame f = new JFrame("My Launcher");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JTextField name = new JTextField("Player", 12);
        JTextField ver = new JTextField("1.20.1", 8);
        JButton play = new JButton("Играть");
        logArea = new JTextArea(14, 50);
        logArea.setEditable(false);
        JPanel top = new JPanel();
        top.add(new JLabel("Ник:")); top.add(name);
        top.add(new JLabel("Версия:")); top.add(ver);
        top.add(play);
        f.add(top, BorderLayout.NORTH);
        f.add(new JScrollPane(logArea), BorderLayout.CENTER);
        play.addActionListener(e -> {
            play.setEnabled(false);
            new Thread(() -> {
                try {
                    launch(name.getText().trim(), ver.getText().trim());
                } catch (Exception ex) {
                    log("Ошибка: " + ex);
                }
                SwingUtilities.invokeLater(() -> play.setEnabled(true));
            }).start();
        });
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    static void log(String s) {
        SwingUtilities.invokeLater(() -> logArea.append(s + "\n"));
    }

    static String osName() {
        String n = System.getProperty("os.name").toLowerCase();
        if (n.contains("win")) return "windows";
        if (n.contains("mac")) return "osx";
        return "linux";
    }

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static void download(String url, Path p) throws Exception {
        if (Files.exists(p)) return;
        Files.createDirectories(p.getParent());
        Path tmp = Paths.get(p + ".tmp");
        HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static boolean allowed(JsonArray rules) {
        if (rules == null) return true;
        boolean result = false;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            boolean match = true;
            if (r.has("os")) {
                JsonObject o = r.getAsJsonObject("os");
                if (o.has("arch")) match = false;
                if (o.has("name") && !o.get("name").getAsString().equals(OS)) match = false;
            }
            if (r.has("features")) match = false;
            if (match) result = r.get("action").getAsString().equals("allow");
        }
        return result;
    }

    static void extract(Path jar, Path dir) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF")) continue;
                if (!(n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) continue;
                Path out = dir.resolve(Paths.get(n).getFileName().toString());
                try (var in = z.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static String sub(String s, Map<String, String> m) {
        for (var en : m.entrySet()) s = s.replace("${" + en.getKey() + "}", en.getValue());
        return s;
    }

    static void collect(JsonArray arr, Map<String, String> m, List<String> out) {
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                out.add(sub(e.getAsString(), m));
            } else {
                JsonObject o = e.getAsJsonObject();
                if (!allowed(o.getAsJsonArray("rules"))) continue;
                JsonElement val = o.get("value");
                if (val.isJsonArray()) for (JsonElement x : val.getAsJsonArray()) out.add(sub(x.getAsString(), m));
                else out.add(sub(val.getAsString(), m));
            }
        }
    }

    static Path ensureJava(JsonObject v) {
        Path system = Paths.get(System.getProperty("java.home"), "bin", "java");
        try {
            String comp = v.has("javaVersion")
                    ? v.getAsJsonObject("javaVersion").get("component").getAsString()
                    : "jre-legacy";
            String arch = System.getProperty("os.arch").toLowerCase();
            boolean arm = arch.contains("aarch64") || arch.contains("arm64");
            boolean is64 = arch.contains("64");
            String platform;
            if (OS.equals("windows")) platform = arm ? "windows-arm64" : is64 ? "windows-x64" : "windows-x86";
            else if (OS.equals("osx")) platform = arm ? "mac-os-arm64" : "mac-os";
            else platform = is64 ? "linux" : "linux-i386";

            Path dir = ROOT.resolve("runtime").resolve(comp);
            Path exe = OS.equals("windows") ? dir.resolve("bin/java.exe")
                    : OS.equals("osx") ? dir.resolve("jre.bundle/Contents/Home/bin/java")
                    : dir.resolve("bin/java");
            Path done = dir.resolve(".done");
            if (Files.exists(done)) return exe;

            log("Скачиваю Java (" + comp + ")...");
            JsonObject all = parse(get(RUNTIMES));
            JsonArray arr = all.getAsJsonObject(platform).getAsJsonArray(comp);
            String murl = arr.get(0).getAsJsonObject().getAsJsonObject("manifest").get("url").getAsString();
            JsonObject files = parse(get(murl)).getAsJsonObject("files");

            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<?>> futures = new ArrayList<>();
            for (var en : files.entrySet()) {
                JsonObject f = en.getValue().getAsJsonObject();
                Path p = dir.resolve(en.getKey());
                String type = f.get("type").getAsString();
                if (type.equals("directory")) {
                    Files.createDirectories(p);
                } else if (type.equals("file")) {
                    futures.add(ex.submit(() -> {
                        download(f.getAsJsonObject("downloads").getAsJsonObject("raw").get("url").getAsString(), p);
                        if (f.has("executable") && f.get("executable").getAsBoolean()) p.toFile().setExecutable(true);
                        return null;
                    }));
                }
            }
            for (Future<?> fu : futures) fu.get();
            ex.shutdown();
            Files.createFile(done);
            return exe;
        } catch (Exception e) {
            log("Не удалось скачать Java, использую системную: " + e);
            return system;
        }
    }

    static void launch(String player, String ver) throws Exception {
        log("Загружаю список версий...");
        JsonObject manifest = parse(get(MANIFEST));
        String vurl = null;
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (v.get("id").getAsString().equals(ver)) vurl = v.get("url").getAsString();
        }
        if (vurl == null) throw new RuntimeException("Версия не найдена: " + ver);

        JsonObject v = parse(get(vurl));
        Path vdir = ROOT.resolve("versions").resolve(ver);
        Path jar = vdir.resolve(ver + ".jar");
        log("Скачиваю клиент...");
        download(v.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString(), jar);

        Path natives = vdir.resolve("natives");
        Files.createDirectories(natives);
        List<String> cp = new ArrayList<>();
        log("Скачиваю библиотеки...");
        for (JsonElement le : v.getAsJsonArray("libraries")) {
            JsonObject lib = le.getAsJsonObject();
            if (!allowed(lib.getAsJsonArray("rules"))) continue;
            JsonObject dl = lib.getAsJsonObject("downloads");
            if (dl == null) continue;
            if (dl.has("artifact")) {
                JsonObject a = dl.getAsJsonObject("artifact");
                Path p = ROOT.resolve("libraries").resolve(a.get("path").getAsString());
                download(a.get("url").getAsString(), p);
                cp.add(p.toString());
                if (p.getFileName().toString().contains("natives")) extract(p, natives);
            }
            if (lib.has("natives") && dl.has("classifiers")) {
                JsonObject nat = lib.getAsJsonObject("natives");
                if (nat.has(OS)) {
                    String key = nat.get(OS).getAsString().replace("${arch}", "64");
                    JsonObject c = dl.getAsJsonObject("classifiers").getAsJsonObject(key);
                    if (c != null) {
                        Path p = ROOT.resolve("libraries").resolve(c.get("path").getAsString());
                        download(c.get("url").getAsString(), p);
                        extract(p, natives);
                    }
                }
            }
        }
        cp.add(jar.toString());

        log("Скачиваю ресурсы (первый запуск долгий)...");
        JsonObject ai = v.getAsJsonObject("assetIndex");
        String assetId = ai.get("id").getAsString();
        Path idx = ROOT.resolve("assets/indexes/" + assetId + ".json");
        download(ai.get("url").getAsString(), idx);
        JsonObject objs = parse(Files.readString(idx)).getAsJsonObject("objects");
        ExecutorService ex = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (var en : objs.entrySet()) {
            String h = en.getValue().getAsJsonObject().get("hash").getAsString();
            Path p = ROOT.resolve("assets/objects/" + h.substring(0, 2) + "/" + h);
            futures.add(ex.submit(() -> {
                download("https://resources.download.minecraft.net/" + h.substring(0, 2) + "/" + h, p);
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        ex.shutdown();

        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", player);
        m.put("version_name", ver);
        m.put("game_directory", ROOT.toString());
        m.put("assets_root", ROOT.resolve("assets").toString());
        m.put("assets_index_name", assetId);
        m.put("auth_uuid", uuid);
        m.put("auth_access_token", "0");
        m.put("user_type", "legacy");
        m.put("version_type", "release");
        m.put("natives_directory", natives.toString());
        m.put("launcher_name", "MyLauncher");
        m.put("launcher_version", "1.0");
        m.put("classpath", String.join(File.pathSeparator, cp));
        m.put("clientid", "0");
        m.put("auth_xuid", "0");

        List<String> cmd = new ArrayList<>();
        cmd.add(ensureJava(v).toString());
        cmd.add("-Xmx2G");
        if (v.has("arguments")) {
            JsonObject args = v.getAsJsonObject("arguments");
            collect(args.getAsJsonArray("jvm"), m, cmd);
            cmd.add(v.get("mainClass").getAsString());
            collect(args.getAsJsonArray("game"), m, cmd);
        } else {
            cmd.add("-Djava.library.path=" + natives);
            cmd.add("-cp");
            cmd.add(m.get("classpath"));
            cmd.add(v.get("mainClass").getAsString());
            for (String s : v.get("minecraftArguments").getAsString().split(" ")) cmd.add(sub(s, m));
        }

        log("Запускаю игру...");
        new ProcessBuilder(cmd).directory(ROOT.toFile()).inheritIO().start();
    }
}
