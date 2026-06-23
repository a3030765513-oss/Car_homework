package com.substation.display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 通过已打包的 car JAR 异步启动动态添加的小车进程 */
final class DynamicCarLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicCarLauncher.class);

    static final String CAR_JAR_RELATIVE = "car/target/car-1.0-SNAPSHOT.jar";
    static final String DYNAMIC_FLAG = "--dynamic";

    private DynamicCarLauncher() {
    }

    static boolean isJarAvailable(Path projectRoot) {
        return Files.isRegularFile(projectRoot.resolve(CAR_JAR_RELATIVE));
    }

    static Path jarPath(Path projectRoot) {
        return projectRoot.resolve(CAR_JAR_RELATIVE).normalize();
    }

    static void launchAsync(String carId, Path projectRoot) {
        Thread launcher = new Thread(() -> launchBlocking(carId, projectRoot), "car-launch-" + carId);
        launcher.setDaemon(true);
        launcher.start();
    }

    private static void launchBlocking(String carId, Path projectRoot) {
        Path carJar = jarPath(projectRoot);
        if (!Files.isRegularFile(carJar)) {
            throw new IllegalStateException(
                "未找到 car JAR: " + carJar + "，请先执行 .\\mvnw.cmd package -pl car -am -DskipTests");
        }

        try {
            Path logDir = projectRoot.resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("car-" + carId + ".log");

            List<String> command = new ArrayList<>();
            command.add(resolveJavaExecutable());
            command.add("-jar");
            command.add(carJar.toString());
            command.add(carId);
            command.add(DYNAMIC_FLAG);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logFile.toFile());
            processBuilder.start();

            LOG.info("已启动小车进程 {}，日志: {}", carId, logFile);
        } catch (IOException e) {
            throw new IllegalStateException("启动小车进程失败: " + carId, e);
        }
    }

    private static String resolveJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path windowsJava = javaHome.resolve("bin").resolve("java.exe");
        if (Files.exists(windowsJava)) {
            return windowsJava.toString();
        }
        Path unixJava = javaHome.resolve("bin").resolve("java");
        if (Files.exists(unixJava)) {
            return unixJava.toString();
        }
        return "java";
    }
}
