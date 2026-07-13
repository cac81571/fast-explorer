package com.fastexplorer.fs;

import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.TaskProgress;
import com.fastexplorer.util.PathUtil;
import com.fastexplorer.util.ProgressThrottle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * コピー元ベースからの相対パスを維持して、ファイルをコピー先へ実コピーする。
 */
public final class FileCopyService {

    public CopyResult copyFiles(
            List<FileEntry> entries,
            Path destDir,
            Path hierarchyBase,
            AtomicBoolean cancel,
            long progressStart,
            Consumer<TaskProgress> onProgress
    ) throws IOException {
        long start = System.nanoTime();
        Path normalizedDestDir = PathUtil.resolveForAccess(destDir);
        Path normalizedBase = PathUtil.resolveForAccess(hierarchyBase);

        Map<String, CopyJob> jobs = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            if (entry.directory()) {
                continue;
            }
            Path source = PathUtil.resolveForAccess(entry.path());
            Path dest = PathUtil.resolveHierarchyCopyDestination(normalizedDestDir, source, normalizedBase);
            jobs.putIfAbsent(PathUtil.toDisplay(dest), new CopyJob(source, dest));
        }

        int total = jobs.size();
        ProgressThrottle throttle = ProgressThrottle.of(onProgress, "コピー", total, progressStart);
        throttle.reportForce(0);

        int copied = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        int index = 0;
        for (CopyJob job : jobs.values()) {
            if (cancel.get()) {
                break;
            }
            index++;
            if (cancel.get() || Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                Path parent = job.dest().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(
                        job.source(),
                        job.dest(),
                        StandardCopyOption.REPLACE_EXISTING
                );
                copied++;
            } catch (IOException ex) {
                if (cancel.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                failed++;
                String message = PathUtil.toDisplay(job.source()) + " → "
                        + PathUtil.toDisplay(job.dest()) + ": "
                        + (ex.getMessage() != null ? ex.getMessage() : ex.toString());
                if (errors.size() < 20) {
                    errors.add(message);
                }
            }
            throttle.report(index);
        }

        throttle.reportForce(index);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        boolean cancelled = cancel.get() || Thread.currentThread().isInterrupted();
        return new CopyResult(total, copied, failed, cancelled, elapsedMs, List.copyOf(errors));
    }

    public record CopyResult(
            int total,
            int copied,
            int failed,
            boolean cancelled,
            long elapsedMs,
            List<String> errors
    ) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (cancelled) {
                sb.append("コピーをキャンセルしました");
            } else if (failed == 0) {
                sb.append("コピー完了");
            } else {
                sb.append("コピー完了（一部失敗）");
            }
            sb.append(": ").append(copied).append(" / ").append(total).append(" 件");
            if (failed > 0) {
                sb.append("  失敗 ").append(failed).append(" 件");
            }
            sb.append("  |  ").append(elapsedMs).append(" ms");
            return sb.toString();
        }
    }

    private record CopyJob(Path source, Path dest) {}
}
