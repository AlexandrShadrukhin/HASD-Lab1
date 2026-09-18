package ru.itmo.hasd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

final class DatasetDownloader {
    private static final URI DATASET_URI = URI.create(
            "https://huggingface.co/datasets/codesignal/lending-club-loan-accepted/resolve/main/accepted_2007_to_2018Q4.csv");
    private static final long MIN_EXPECTED_SIZE = 500L * 1024 * 1024;
    private static final long PROGRESS_STEP = 100L * 1024 * 1024;

    private DatasetDownloader() { }

    static Path ensureDataset(Path projectRoot) throws IOException, InterruptedException {
        Path dataDirectory = projectRoot.resolve("data");
        Path dataset = dataDirectory.resolve("loan.csv");
        if (Files.isRegularFile(dataset) && Files.size(dataset) >= MIN_EXPECTED_SIZE) {
            System.out.println("Dataset already exists, download skipped: " + projectRoot.relativize(dataset));
            printSize(Files.size(dataset));
            return dataset;
        }

        Files.createDirectories(dataDirectory);
        Path partial = dataDirectory.resolve("loan.csv.part");
        Files.deleteIfExists(partial);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(DATASET_URI)
                .timeout(Duration.ofHours(2))
                .header("User-Agent", "HASD-Lab1/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Dataset download failed: HTTP " + response.statusCode());
        }

        long downloaded = 0;
        long nextProgress = PROGRESS_STEP;
        long lastProgressTime = System.nanoTime();
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                downloaded += read;
                long now = System.nanoTime();
                if (downloaded >= nextProgress || now - lastProgressTime >= 5_000_000_000L) {
                    System.out.printf(Locale.ROOT, "Downloading dataset: %.0f MB%n", downloaded / 1_048_576.0);
                    nextProgress = ((downloaded / PROGRESS_STEP) + 1) * PROGRESS_STEP;
                    lastProgressTime = now;
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Dataset download was interrupted; partial file kept at " + partial, e);
        }

        long size = Files.size(partial);
        if (size < MIN_EXPECTED_SIZE) {
            throw new IOException("Downloaded dataset is unexpectedly small: " + size + " bytes");
        }
        try {
            Files.move(partial, dataset, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, dataset, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Dataset downloaded: " + projectRoot.relativize(dataset));
        printSize(size);
        return dataset;
    }

    static void printSize(long bytes) {
        System.out.printf(Locale.ROOT, "Size: %.2f GB%n", bytes / 1_073_741_824.0);
    }
}
