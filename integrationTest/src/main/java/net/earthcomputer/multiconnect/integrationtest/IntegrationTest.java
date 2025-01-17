package net.earthcomputer.multiconnect.integrationtest;

import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.connect.ConnectionMode;
import net.earthcomputer.multiconnect.impl.TestingAPI;
import net.earthcomputer.multiconnect.protocols.generic.AssetDownloader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class IntegrationTest implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Logger SERVER_LOGGER = LogManager.getLogger("Server");
    private static final AtomicReference<ServerHandle> currentServer = new AtomicReference<>();
    private static final ReadWriteLock serverStopWait = new ReentrantReadWriteLock();

    private static int lastStartedProtocol;
    private static BiConsumer<String, String> addFailureFunc;

    @Override
    public void onInitialize() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopServer(false)));
        TestingAPI.setUnexpectedDisconnectListener(t -> addFailure(t.toString(), t));
    }

    public static void syncMacrosFolder() throws IOException, URISyntaxException {
        Path jsMacrosDir = FabricLoader.getInstance().getConfigDir().resolve("jsMacros");
        if (Files.isSymbolicLink(jsMacrosDir)) {
            return;
        }

        recursiveDelete(jsMacrosDir);
        if (!Files.exists(jsMacrosDir)) {
            Files.createDirectories(jsMacrosDir);
        }

        File modFile = new File(IntegrationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (modFile.isDirectory()) {
            modFile = new File(modFile.getAbsolutePath().replace("classes" + File.separator + "java", "resources"));
            Path fromJsMacrosDir = modFile.toPath().resolve("jsMacros");
            try (Stream<Path> paths = Files.walk(fromJsMacrosDir)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    if (!Files.isDirectory(path)) {
                        try (InputStream from = Files.newInputStream(path)) {
                            copyFile(fromJsMacrosDir.relativize(path).toString().replace(File.separator, "/"), from, jsMacrosDir);
                        }
                    }
                }
            }
        } else {
            try (JarFile modJar = new JarFile(modFile)) {
                Enumeration<JarEntry> entries = modJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("jsMacros/") && !entry.isDirectory()) {
                        copyFile(entry.getName().substring("jsMacros/".length()), modJar.getInputStream(entry), jsMacrosDir);
                    }
                }
            }
        }
    }

    public static void setAddFailureFunc(BiConsumer<String, String> func) {
        addFailureFunc = func;
    }

    public static void addFailure(String description, @Nullable String stackTrace) {
        if (addFailureFunc != null) {
            addFailureFunc.accept(description, stackTrace);
        }
    }

    public static void addFailure(String description) {
        addFailure(description, (String) null);
    }

    public static void addFailure(String description, @Nullable Throwable throwable) {
        String stackTrace = null;
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            stackTrace = sw.toString();
        }
        addFailure(description, stackTrace);
    }

    private static void recursiveDelete(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isDirectory(file)) {
                    recursiveDelete(file);
                }
                Files.delete(file);
            }
        }
    }

    private static void copyFile(String name, InputStream from, Path jsMacrosDir) throws IOException {
        String[] parts = name.split("/");
        Path destPath = jsMacrosDir;
        for (int i = 0; i < parts.length - 1; i++) {
            destPath = destPath.resolve(parts[i]);
            if (!Files.exists(destPath)) {
                Files.createDirectory(destPath);
            }
        }
        destPath = destPath.resolve(parts[parts.length - 1]);
        Files.copy(from, destPath);
    }

    private static final Pattern SERVER_STARTED_PATTERN = Pattern.compile("Done \\([\\d.,]+s\\)! For help, type \"help\"");
    private static final Pattern JOINED_GAME_PATTERN = Pattern.compile("(\\w+) joined the game");
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static String setupServer(int protocol) throws IOException {
        lastStartedProtocol = protocol;
        String versionName = ConnectionMode.byValue(protocol).getName();
        LOGGER.info("Starting server version {}", versionName);
        long startTime = System.nanoTime();

        File serverJar = AssetDownloader.downloadServer(versionName);
        if (serverJar == null) {
            throw new IOException("Failed to download server");
        }
        File serverDir = new File("integrationTestServer");
        if (serverDir.exists()) {
            FileUtils.deleteDirectory(serverDir);
        }
        serverDir.mkdirs();
        Files.copy(serverJar.toPath(), serverDir.toPath().resolve("server.jar"));
        Files.writeString(serverDir.toPath().resolve("eula.txt"), "eula=true");
        int port = Integer.getInteger("multiconnect.integrationTest.port", 25564);
        Properties serverProperties = new Properties();
        serverProperties.setProperty("enable-command-block", "true");
        serverProperties.setProperty("generate-structures", "false");
        serverProperties.setProperty("level-type", "flat");
        serverProperties.setProperty("online-mode", "false");
        serverProperties.setProperty("server-port", String.valueOf(port));
        serverProperties.setProperty("sync-chunk-writes", "false");
        if (protocol <= Protocols.V1_11_2) {
            serverProperties.setProperty("use-native-transport", "false");
        }
        try (Writer writer = new FileWriter(new File(serverDir, "server.properties"))) {
            serverProperties.store(writer, "");
            writer.flush();
        }

        String ip = startServer(protocol);

        LOGGER.info("Server started! Took %.3f seconds".formatted((System.nanoTime() - startTime) / 1000000000.0));

        return ip;
    }

    private static String startServer(int protocol) throws IOException {
        File serverDir = new File("integrationTestServer");

        ServerHandle serverHandle = new ServerHandle();
        if (!currentServer.compareAndSet(null, serverHandle)) {
            throw new IllegalStateException("Cannot start a server when a server is already running");
        }

        String javaExePath = Util.getOperatingSystem() == Util.OperatingSystem.WINDOWS ? "java.exe" : "java";
        javaExePath = new File(new File(System.getProperty("java.home"), "bin"), javaExePath).getAbsolutePath();
        Process server = new ProcessBuilder(javaExePath, "-jar", "server.jar", "nogui").directory(serverDir).start();

        serverHandle.stdin = new PrintWriter(new OutputStreamWriter(server.getOutputStream()));

        Semaphore serverStarted = new Semaphore(0);
        Thread serverOutputThread = new Thread(() -> {
            serverStopWait.writeLock().lock();
            Set<String> alreadyInitializedPlayers = new HashSet<>();
            boolean hasServerStarted = false;
            Scanner scanner = new Scanner(server.getInputStream());
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                SERVER_LOGGER.info(line);
                if (!hasServerStarted && SERVER_STARTED_PATTERN.matcher(line).find()) {
                    hasServerStarted = true;
                    serverStarted.release();
                }
                Matcher joinedGameMatcher = JOINED_GAME_PATTERN.matcher(line);
                if (joinedGameMatcher.find()) {
                    String playerName = joinedGameMatcher.group(1);
                    if (alreadyInitializedPlayers.add(playerName)) {
                        serverHandle.stdin.printf("op %s\n", playerName);
                        if (protocol <= Protocols.V1_12_2) {
                            serverHandle.stdin.printf("tp %s 0 ~ 0\n", playerName);
                        } else {
                            serverHandle.stdin.printf("execute at %s run teleport 0 ~ 0\n", playerName);
                        }
                        serverHandle.stdin.flush();
                    }
                }
            }

            currentServer.set(null);
            serverStopWait.writeLock().unlock();
        });
        serverOutputThread.setName("Server Output Thread");
        serverOutputThread.start();

        try {
            serverStarted.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        serverHandle.stdin.println("gamerule doMobSpawning false");
        serverHandle.stdin.println("gamerule doWeatherCycle false");
        serverHandle.stdin.println("setworldspawn 0 0 0");
        serverHandle.stdin.println("gamerule spawnRadius 0");
        serverHandle.stdin.flush();

        int port = Integer.getInteger("multiconnect.integrationTest.port", 25564);
        return "127.0.0.1:" + port;
    }

    public static void stopServer() {
        stopServer(true);
    }

    private static void stopServer(boolean wait) {
        ServerHandle serverHandle = currentServer.get();
        if (serverHandle != null) {
            if (!serverHandle.isStopping.getAndSet(true)) {
                serverHandle.stdin.println("stop");
                serverHandle.stdin.flush();
            }
            if (wait) {
                serverStopWait.readLock().lock();
                serverStopWait.readLock().unlock();
            }
        }
    }

    public static String resetServer() throws IOException {
        return restartServer(true);
    }

    public static String restartServer() throws IOException {
        return restartServer(false);
    }

    private static String restartServer(boolean resetWorld) throws IOException {
        long startTime = System.nanoTime();

        stopServer();

        if (resetWorld) {
            File worldDir = new File("integrationTest", "world");
            if (worldDir.exists()) {
                FileUtils.deleteDirectory(worldDir);
            }
        }

        String ip = startServer(lastStartedProtocol);

        LOGGER.info("Server restarted! Took %.3f seconds".formatted((System.nanoTime() - startTime) / 1000000000.0));

        return ip;
    }

    private static class ServerHandle {
        AtomicBoolean isStopping = new AtomicBoolean(false);
        PrintWriter stdin;
    }
}
