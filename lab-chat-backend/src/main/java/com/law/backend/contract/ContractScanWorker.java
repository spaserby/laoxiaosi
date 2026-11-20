package com.law.backend.contract;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.law.backend.contract.ContractScanStore.*;

@Slf4j
@Component
final class ContractScanWorker {
    private final ContractScanStore store;
    private final ContractScanService service;
    private final ContractScanDocuments documents;
    private final ContractScanReview review;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "contract-scan-worker"));

    ContractScanWorker(ContractScanStore store, ContractScanService service, ContractScanDocuments documents, ContractScanReview review) {
        this.store = store;
        this.service = service;
        this.documents = documents;
        this.review = review;
    }

    @Scheduled(fixedDelay = 1800)
    void dispatch() {
        if (!service.policy().enabled() || !running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                Job job = service.take();
                if (job != null) process(job);
            } catch (IOException | RuntimeException e) {
                log.warn("合同任务暂未推进，后台将重新检查");
            } finally {
                running.set(false);
            }
        });
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    void cleanup() {
        try { service.cleanup(); }
        catch (IOException error) { log.warn("合同任务回收未完成，将在下次重试"); }
    }

    void process(Job job) throws IOException {
        try {
            if (job.inputs.get(0).pdf()) {
                try (var pdf = documents.pdf(store.asset(job, job.inputs.get(0)))) {
                    job.pages = pdf.pages();
                    service.update(job, State.SCANNING, "");
                    for (int page = 1; page <= job.pages && service.active(job); page++) scan(job, page, pdf);
                } catch (IOException | IllegalArgumentException error) {
                    if (job.pages == 0) {
                        service.update(job, State.FAILED, "PDF 无法读取、已加密或超过 " + service.policy().maxPdfPages() + " 页");
                        return;
                    }
                    throw error;
                }
            } else {
                job.pages = job.inputs.size();
                service.update(job, State.SCANNING, "");
                for (int page = 1; page <= job.pages && service.active(job); page++) scan(job, page, null);
            }
            if (!service.active(job)) return;
            service.update(job, State.REVIEWING, "");
            compileReport(job);
        } catch (IOException | RuntimeException error) {
            service.update(job, State.FAILED, "合同处理未完成，已有页面结果可以继续使用");
        }
    }

    private void scan(Job job, int page, ContractScanDocuments.Pdf pdf) throws IOException {
        var checkpoint = store.source(job, page);
        if (store.read(checkpoint, ScanPage.class) != null) return;
        ScanPage scanned;
        try {
            if (pdf != null) {
                scanned = pdf.scan(page, () -> service.useModel(job, true));
            } else if (service.useModel(job, true)) {
                var input = job.inputs.get(page - 1);
                scanned = documents.photo(store.asset(job, input), input.format());
            } else {
                scanned = new ScanPage("", "今日识别额度已用完，可稍后重试", false, true);
            }
        } catch (IOException | RuntimeException e) {
            scanned = new ScanPage("", "此页无法读取，可替换原图或重试", false, pdf == null);
        }
        service.checkpoint(job, checkpoint, scanned);
    }

    private void compileReport(Job job) throws IOException {
        var pages = new ArrayList<ScanPage>();
        for (int page = 1; page <= job.pages; page++) pages.add(store.read(store.source(job, page), ScanPage.class));
        var terms = new ArrayList<Term>();
        var sourceTexts = new HashMap<Integer, String>();
        boolean allPages = true;
        int reviewedCount = 0;
        for (int page = 1; page <= job.pages && service.active(job); page++) {
            ScanPage scanned = pages.get(page - 1);
            if (scanned == null || scanned.text().isBlank()) {
                allPages = false;
                continue;
            }
            PageReview checked = store.read(store.review(job, page), PageReview.class);
            if (checked == null) {
                try {
                    checked = review.page(job.stance, page, scanned.text(), near(pages, page - 2), near(pages, page), () -> service.useModel(job, false));
                    service.checkpoint(job, store.review(job, page), checked);
                } catch (IOException | RuntimeException error) {
                    allPages = false;
                    continue;
                }
            }
            reviewedCount++;
            terms.addAll(checked.terms());
            sourceTexts.put(page, scanned.text());
            allPages &= scanned.scanned() && scanned.warning() == null;
        }
        if (!service.active(job)) return;
        CrossReview cross = null;
        if (reviewedCount > 0) {
            try {
                cross = store.read(store.cross(job), CrossReview.class);
                if (cross == null) {
                    cross = review.cross(job.stance, terms, sourceTexts, () -> service.useModel(job, false));
                    service.checkpoint(job, store.cross(job), cross);
                }
            } catch (IOException | RuntimeException ignored) { /* Completed page reviews remain available. */ }
        }
        if (reviewedCount == 0) service.update(job, State.FAILED, "暂未完成页面审查，请查看失败页并重试");
        else if (allPages && cross != null && cross.complete()) service.update(job, State.READY, "");
        else service.update(job, State.PARTIAL, cross == null ? "跨页核对未完成，可重新汇总"
                : cross.complete() ? "部分页面需要核对或重试" : "跨页约定较多，已分批核对，请结合原件复查");
    }

    private static String near(java.util.List<ScanPage> pages, int index) {
        ScanPage page = index < 0 || index >= pages.size() ? null : pages.get(index);
        return page == null ? "" : page.text();
    }

    @PreDestroy void close() { executor.shutdownNow(); }
}
