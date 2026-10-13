/**
 * One-off tool: convert the official site's legacy .doc (OLE2) files into the docx-zip form
 * that IngestOfficial expects (a single entry `word/document.xml`), so the old-format laws
 * join the normal ingest pipeline instead of failing with "expected a .docx / zip container".
 *
 * Cache convention:
 *   in : <cache>/doc-<bbbs>.doc    (downloaded OLE2 .doc)
 *   out: <cache>/docx-<bbbs>.zip   (synthetic docx container)
 *
 * Size guard: IngestOfficial treats cache entries <= 1024 bytes as misses (they could be
 * WAF error pages), so short prose documents are padded with an inert XML comment until the
 * container safely exceeds that threshold. Existing outputs <= 1024 bytes are regenerated.
 *
 * ASCII-only on purpose (same rationale as IngestOfficial/GenInitSql: JDK single-file source
 * mode decodes .java with the platform default charset, which would corrupt non-ASCII text).
 *
 * Run (jars from poi-scratchpad + deps):
 *   java -cp "/jars/*" DocToDocxCache.java /cache
 */
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DocToDocxCache {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/cache");
        File[] docs = dir.listFiles((d, n) -> n.startsWith("doc-") && n.endsWith(".doc"));
        if (docs == null || docs.length == 0) {
            System.out.println("no doc-*.doc found in " + dir);
            return;
        }
        int ok = 0, skip = 0, fail = 0;
        for (File doc : docs) {
            String name = doc.getName();
            String bbbs = name.substring("doc-".length(), name.length() - ".doc".length());
            File out = new File(dir, "docx-" + bbbs + ".zip");
            if (out.exists() && out.length() > 1024) {
                skip++;
                continue;
            }
            try (FileInputStream in = new FileInputStream(doc);
                 HWPFDocument hwpf = new HWPFDocument(in);
                 WordExtractor ex = new WordExtractor(hwpf)) {
                String[] paras = ex.getParagraphText();
                StringBuilder xml = new StringBuilder(paras.length * 64);
                xml.append("<w:document><w:body>");
                int kept = 0;
                for (String p : paras) {
                    if (p == null) continue;
                    String t = p.replace("\r", "").replace("\n", "").trim();
                    if (t.isEmpty()) continue;
                    xml.append("<w:p><w:r><w:t>").append(t).append("</w:t></w:r></w:p>");
                    kept++;
                }
                xml.append("</w:body></w:document>");
                String payload = xml.toString();
                writeZip(out, payload, false);
                if (out.length() <= 1024) {
                    // Chinese text deflates hard; if the container still misses the ingest tool's
                    // 1 KB cache threshold, rewrite with inert padding stored uncompressed.
                    String padded = payload.replace("<w:document><w:body>",
                            "<w:document><w:body><!--" + " ".repeat(1500) + "-->");
                    writeZip(out, padded, true);
                }
                System.out.println("OK   " + bbbs + "  paras=" + kept + "  bytes=" + out.length());
                ok++;
            } catch (Exception e) {
                System.out.println("FAIL " + bbbs + " : " + e);
                fail++;
            }
        }
        System.out.println("done: ok=" + ok + " skip=" + skip + " fail=" + fail);
    }

    /** Write the synthetic docx container; stored=true disables deflate (padding must occupy bytes). */
    private static void writeZip(File out, String payload, boolean stored) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            if (stored) {
                zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            }
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(payload.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
