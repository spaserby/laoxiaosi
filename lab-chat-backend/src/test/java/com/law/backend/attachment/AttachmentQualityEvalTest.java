package com.law.backend.attachment;

import com.fasterxml.jackson.databind.*;
import com.law.backend.rag.*;
import com.law.backend.service.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.List;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in component experiment: real production chunker/retrieval + cached real model outputs.
 * No Spring context, business database, session, upload store, or production configuration is loaded.
 * Maven: -Dtest=AttachmentQualityEvalTest -Dattachment.eval.phase=prepare
 */
@EnabledIfSystemProperty(named = "attachment.eval.phase", matches = ".+")
class AttachmentQualityEvalTest {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final String EMBEDDING = "qwen3.7-text-embedding-flash", RERANK = "gte-rerank-v2", CHAT = "deepseek-v4-flash";
    static final List<Config> CONFIGS = List.of(new Config("baseline", 1536, 512, 128),
            new Config("child256", 1536, 256, 128), new Config("child768", 1536, 768, 128),
            new Config("parent768", 768, 512, 128), new Config("parent2304", 2304, 512, 128),
            new Config("overlap0", 1536, 512, 0));
    record Config(String id, int parent, int child, int overlap) {}
    final Path directory = Path.of(System.getProperty("attachment.eval.dir", "target/attachment-quality"));
    final Map<String, String> documents = new LinkedHashMap<>(), names = new LinkedHashMap<>();
    final Map<String, AttachmentQualityMetrics.Evidence> evidence = new LinkedHashMap<>();
    final Map<String, Map<String, AttachmentChunker.Chunks>> indexes = new LinkedHashMap<>();
    JsonNode dataset;

    @Test void experiment() throws Exception {
        Files.createDirectories(directory);
        dataset = JSON.readTree(Objects.requireNonNull(getClass().getResourceAsStream("/golden/attachment-quality-set.json")));
        buildCorpus();
        for (Config config : CONFIGS) {
            var properties = properties(config);
            var chunker = new AttachmentChunker(properties, new AttachmentTokens(properties));
            Map<String, AttachmentChunker.Chunks> index = new LinkedHashMap<>();
            documents.forEach((id, text) -> index.put(id, chunker.split(text, List.of(new AttachmentChunker.Source(0, text.length(), "合成正文")))));
            indexes.put(config.id(), index);
        }
        switch (System.getProperty("attachment.eval.phase")) {
            case "prepare" -> prepare();
            case "rank-dev" -> rank("dev", CONFIGS);
            case "evaluate-dev" -> evaluate("dev", CONFIGS);
            case "rank-holdout" -> rank("holdout", finalists());
            case "evaluate-holdout" -> evaluate("holdout", finalists());
            case "parse" -> parse();
            case "generation" -> scoreGeneration();
            default -> throw new IllegalArgumentException("Unknown evaluation phase");
        }
    }
    FileProperties properties(Config config) {
        var p = new FileProperties(); p.setParentTokens(config.parent()); p.setChildTokens(config.child()); p.setOverlapTokens(config.overlap());
        return p;
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String embeddingKey(String text) { return hash(EMBEDDING + "\n" + text); }
    void write(String filename, Object value) throws Exception {
        Path temp = directory.resolve(filename + ".tmp");
        JSON.writeValue(temp.toFile(), value);
        Files.move(temp, directory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }
    Map<String, Object> jobs(String kind, Collection<?> items) {
        return Map.of("version", 1, "kind", kind, "model", switch(kind) { case "embedding" -> EMBEDDING; case "rerank" -> RERANK; default -> CHAT; }, "items", items);
    }
    void buildCorpus() {
        for (JsonNode doc : dataset.get("documents")) {
            String id = doc.get("id").asText(); StringBuilder text = new StringBuilder();
            for (JsonNode part : doc.get("segments")) {
                if (part.has("heading")) text.append(part.get("heading").asText()).append('\n');
                if (part.has("padding")) text.append(padding(part.get("padding").asInt()));
                if (part.has("hardPadding")) text.append("记录abcdef0123456789".repeat(part.get("hardPadding").asInt()));
                if (part.has("text")) {
                    String sentence = part.get("text").asText(); int start = text.length(); text.append(sentence);
                    String eid = part.get("id").asText();
                    if (evidence.put(eid, new AttachmentQualityMetrics.Evidence(eid, id, start, text.length(), sentence)) != null)
                        throw new IllegalStateException("Duplicate gold evidence ID");
                    if (!part.has("hardSuffix")) text.append('\n');
                }
                if (part.has("hardSuffix")) text.append("记录abcdef0123456789".repeat(part.get("hardSuffix").asInt())).append('\n');
            }
            documents.put(id, text.toString()); names.put(id, doc.get("name").asText());
        }
        for (JsonNode q : dataset.get("questions")) for (JsonNode eid : q.get("evidence")) {
            var unit = Objects.requireNonNull(evidence.get(eid.asText()), "Unknown gold evidence");
            assertTrue(strings(q.get("docs")).contains(unit.fileId()));
            assertEquals(unit.text(), documents.get(unit.fileId()).substring(unit.start(), unit.end()));
            assertEquals(documents.get(unit.fileId()).indexOf(unit.text()), documents.get(unit.fileId()).lastIndexOf(unit.text()), "Gold quotes must be unique");
        }
        assertEquals(24, dataset.get("questions").size());
        assertTrue(documents.values().stream().allMatch(s -> s.length() <= 200000));
    }
    String padding(int chars) {
        String[] topics = {"例行资料整理只记录文件版本与交接状态，不变更其他条款。", "双方按约定维护设备清单、服务记录和日常联系机制。",
                "台账记录标识、归档顺序与附件名称，具体业务事项见对应条款。", "培训、验收、付款、保修与保证金等事项应按对应约定分别核对。",
                "资料接收登记包含文件份数、记录类型与检查结果，编号仅作索引。", "现场人员整理清单后按既定流程提交，相关签收只证明收到材料。"};
        StringBuilder out = new StringBuilder(); int n = 0;
        while (out.length() < chars) out.append("背景记录").append(++n).append("：").append(topics[(n * 7 + 3) % topics.length]).append('\n');
        return out.toString();
    }
    static List<String> strings(JsonNode node) { List<String> out = new ArrayList<>(); node.forEach(n -> out.add(n.asText())); return out; }
    int budget() {
        int value = Integer.getInteger("attachment.eval.budget", dataset.get("contextBudget").asInt());
        if (value < 512 || value > 8000) throw new IllegalArgumentException("Evaluation budget must be 512..8000");
        return value;
    }
    List<Config> finalists() throws Exception {
        String winner = JSON.readTree(directory.resolve("selection.json").toFile()).get("candidate").asText();
        return CONFIGS.stream().filter(c -> c.id().equals("baseline") || c.id().equals(winner)).toList();
    }
    Map<String, Object> indexStats(Config config) {
        var chunks = indexes.get(config.id()).values(); var tokens = new AttachmentTokens(properties(config));
        int overlapPairs = 0, parents = 0, children = 0, estimated = 0;
        for (var index : chunks) {
            parents += index.parents().size(); children += index.children().size();
            for (var child : index.children()) estimated += tokens.embedding(child.source() + "\n" + child.text());
            for (int i = 1; i < index.children().size(); i++) {
                var a = index.children().get(i-1); var b = index.children().get(i);
                if (a.parentId() == b.parentId() && b.start() < a.end()) overlapPairs++;
            }
        }
        return Map.of("config", config, "parents", parents, "children", children, "overlappingChildPairs", overlapPairs, "estimatedEmbeddingTokens", estimated);
    }
    void prepare() throws Exception {
        Map<String, Object> items = new LinkedHashMap<>();
        for (var index : indexes.values()) for (var chunks : index.values()) for (var c : chunks.children()) {
            String input = c.source() + "\n" + c.text();
            items.putIfAbsent(embeddingKey(input), Map.of("key", embeddingKey(input), "input", input, "role", "document"));
        }
        for (JsonNode q : dataset.get("questions")) {
            String input = q.get("question").asText();
            items.putIfAbsent(embeddingKey(input), Map.of("key", embeddingKey(input), "input", input, "role", "query"));
        }
        write("embedding-jobs.json", jobs("embedding", items.values()));
        write("corpus.json", Map.of("datasetVersion", dataset.get("version").asText(), "documents", documents, "evidence", evidence.values(),
                "questions", dataset.get("questions"), "indexStats", CONFIGS.stream().map(this::indexStats).toList(),
                "selectionRule", "dev all-required evidence, then micro recall, then fewer indexed children, then config ID; holdout not used"));
        fixtures();
        System.out.println("ATTACHMENT_EVAL prepared documents=" + documents.size() + " questions=24 configurations=" + CONFIGS.size() + " uniqueEmbeddingInputs=" + items.size());
    }
    Map<String, JsonNode> cache(String kind, boolean optional) throws Exception {
        Path path = directory.resolve("model-" + kind + ".json");
        if (optional && !Files.exists(path)) return Map.of();
        JsonNode file = JSON.readTree(path.toFile());
        String expected = switch(kind) { case "embedding" -> EMBEDDING; case "rerank" -> RERANK; default -> CHAT; };
        assertEquals(expected, file.path("model").asText(), "Cache model mismatch");
        Map<String, JsonNode> out = new HashMap<>();
        file.get("items").forEach(item -> { if (out.put(item.get("key").asText(), item) != null) throw new IllegalStateException("Duplicate model cache key"); });
        return out;
    }
    float[] vector(Map<String, JsonNode> embeddings, String input) {
        JsonNode item = Objects.requireNonNull(embeddings.get(embeddingKey(input)), "Embedding cache missing");
        assertFalse(item.has("error"), "Real embedding failed; experiment cannot silently change to lexical mode");
        JsonNode values = item.get("embedding"); assertNotNull(values); float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i).floatValue();
        assertEquals(1024, out.length); return out;
    }
    static double cosine(float[] a, float[] b) {
        double dot=0, na=0, nb=0;
        for (int i=0; i<a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na*nb);
    }
    record Runner(AttachmentRetrieval retrieval, List<AttachmentRepository.FileRecord> files, AttachmentRepository.Scope scope) {
        AttachmentContext run(String question, int budget) { return retrieval.retrieve(scope, files, question, budget); }
    }
    Runner runner(Config config, JsonNode question, Map<String, JsonNode> embeddings, Map<String, JsonNode> reranks, Map<String, Object> captured) throws Exception {
        List<String> ids = strings(question.get("docs")); var p = properties(config);
        var repo = mock(AttachmentRepository.class); var embed = mock(AttachmentEmbedding.class); var reranker = mock(RerankService.class);
        var scope = new AttachmentRepository.Scope("synthetic-quality", "u:101", 1000);
        List<AttachmentRepository.ParentRow> parents = new ArrayList<>(); List<AttachmentRepository.Hit> children = new ArrayList<>();
        Map<String, float[]> childVectors = new HashMap<>();
        for (String id : ids) {
            var chunks = indexes.get(config.id()).get(id);
            chunks.parents().forEach(c -> parents.add(new AttachmentRepository.ParentRow(id, c.id(), c.start(), c.end(), c.source(), c.text())));
            for (var c : chunks.children()) {
                children.add(new AttachmentRepository.Hit(id, c.id(), c.parentId(), c.start(), c.end(), c.source(), c.text(), 0));
                childVectors.put(id + ":" + c.id(), vector(embeddings, c.source() + "\n" + c.text()));
            }
        }
        when(repo.parents(scope, ids)).thenReturn(parents); when(repo.children(scope, ids)).thenReturn(children);
        String query = question.get("question").asText(); float[] qv = vector(embeddings, query);
        when(embed.query(query)).thenReturn(qv);
        when(repo.dense(eq(scope), eq(ids), any(), anyInt(), anyString())).thenAnswer(call -> children.stream()
                .map(h -> new AttachmentRepository.Hit(h.fileId(), h.chunkId(), h.parentId(), h.start(), h.end(), h.source(), h.text(), cosine(qv, childVectors.get(h.fileId()+":"+h.chunkId()))))
                .sorted(Comparator.comparingDouble(AttachmentRepository.Hit::score).reversed()).limit(call.getArgument(3, Integer.class)).toList());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(call -> {
            List<String> docs = call.getArgument(1); int top = call.getArgument(2);
            String key = hash(RERANK + "\n" + query + "\n" + JSON.writeValueAsString(docs) + "\n" + top);
            if (captured != null) {
                captured.putIfAbsent(key, Map.of("key", key, "query", query, "documents", docs, "topN", top));
                return IntStream.range(0, Math.min(top, docs.size())).mapToObj(i -> new RerankService.RerankHit(i, 1)).toList();
            }
            JsonNode result = reranks.get(key);
            assertNotNull(result, "Rerank cache missing; do not score an unrecorded fallback");
            if (result.has("error")) throw new IllegalStateException("Recorded real rerank failure");
            List<RerankService.RerankHit> hits = new ArrayList<>();
            for (JsonNode h : result.get("results")) hits.add(new RerankService.RerankHit(h.get("index").asInt(), h.get("score").asDouble()));
            return hits;
        });
        var rag = new RagProperties(); rag.setRerankEnabled(true);
        var files = ids.stream().map(id -> new AttachmentRepository.FileRecord(id, names.get(id), "txt", "READY", "text", documents.get(id).length(), 1, 1, List.of(), List.of(), "", 100000, documents.get(id).length())).toList();
        return new Runner(new AttachmentRetrieval(repo, embed, reranker, rag, p, new AttachmentTokens(p)), files, scope);
    }
    void rank(String split, List<Config> configs) throws Exception {
        var embeddings = cache("embedding", false); Map<String, Object> captured = new LinkedHashMap<>();
        for (Config config : configs) for (JsonNode q : dataset.get("questions")) if (q.get("split").asText().equals(split))
            runner(config, q, embeddings, Map.of(), captured).run(q.get("question").asText(), budget());
        write("rerank-" + split + "-jobs.json", jobs("rerank", captured.values()));
        System.out.println("ATTACHMENT_EVAL " + split + " realRerankRequests=" + captured.size());
    }
    void evaluate(String split, List<Config> configs) throws Exception {
        var embeddings = cache("embedding", false); var reranks = cache("rerank", false);
        List<Map<String, Object>> rows = new ArrayList<>(); List<Map<String, Object>> summary = new ArrayList<>(); List<Map<String, Object>> generation = new ArrayList<>();
        for (Config config : configs) {
            int positive=0, negative=0, required=0, covered=0, all=0, budgetViolations=0, degraded=0; double macro=0;
            List<Integer> lengths = new ArrayList<>();
            for (JsonNode q : dataset.get("questions")) {
                if (!q.get("split").asText().equals(split)) continue;
                Runner run = runner(config, q, embeddings, reranks, null); String query = q.get("question").asText(); int budget = budget();
                AttachmentContext context = run.run(query, budget); // warm-up excluded from measurements
                List<Double> times = new ArrayList<>();
                for (int repeat=0; repeat<3; repeat++) {
                    long start=System.nanoTime(); AttachmentContext repeated=run.run(query, budget);
                    times.add((System.nanoTime()-start)/1_000_000.0); assertEquals(context.text(), repeated.text());
                }
                var gold = strings(q.get("evidence")).stream().map(evidence::get).toList();
                var score = AttachmentQualityMetrics.score(context, gold, documents);
                assertTrue(score.sourceLocationsValid(), "Source offsets must point at actual supplied text");
                int contextTokens = new AttachmentTokens(properties(config)).chat(context.text());
                if (contextTokens > budget) budgetViolations++;
                if (context.warnings().stream().anyMatch(w -> w.contains("暂不可用"))) degraded++;
                lengths.add(contextTokens);
                if (score.required()>0) { positive++; required+=score.required(); covered+=score.covered(); macro+=score.recall(); if(score.allRequired()) all++; } else negative++;
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("config",config.id()); row.put("id",q.get("id").asText()); row.put("split",split); row.put("kind",q.get("kind").asText());
                row.put("score",score); row.put("contextTokensEstimated",contextTokens); row.put("replayMs",times); row.put("context",context);
                rows.add(row);
                if (split.equals("holdout")) {
                    String key=hash("answer-v1\n"+CHAT+"\n"+query+"\n"+context.text());
                    generation.add(Map.of("key",key,"id",q.get("id").asText(),"config",config.id(),"question",query,"context",context.text(),"sourceLabels",context.sources().stream().map(AttachmentContext.Source::label).toList()));
                }
            }
            Map<String,Object> s = new LinkedHashMap<>();
            s.put("config",config.id()); s.put("answerable",positive); s.put("unanswerable",negative); s.put("required",required); s.put("covered",covered); s.put("allRequired",all);
            s.put("microRecall",required==0?null:(double)covered/required); s.put("macroRecall",positive==0?null:macro/positive); s.put("allRequiredRate",positive==0?null:(double)all/positive);
            s.put("meanContextTokensEstimated",lengths.stream().mapToInt(i->i).average().orElse(0)); s.put("budgetViolations",budgetViolations); s.put("degradedQuestions",degraded); s.put("index",indexStats(config));
            summary.add(s);
        }
        write(split+"-results.json",Map.of("split",split,"budget",budget(),"summary",summary,"rows",rows,
                "path","Production chunker/retrieval; cached real embedding/rerank; exhaustive in-memory cosine; no business DB or HTTP session"));
        if(split.equals("dev")) {
            var best=summary.stream().sorted(Comparator.<Map<String,Object>>comparingInt(s->-(Integer)s.get("allRequired"))
                    .thenComparingDouble(s->-(Double)s.get("microRecall"))
                    .thenComparingInt(s->((Number)((Map<?,?>)s.get("index")).get("children")).intValue())
                    .thenComparing(s->s.get("config").toString())).findFirst().orElseThrow();
            write("selection.json",Map.of("baseline","baseline","candidate",best.get("config"),"selectionSplit","dev",
                    "rule","allRequired desc, microRecall desc, indexed children asc, ID asc; holdout excluded"));
        } else write("generation-jobs.json", jobs("generation",generation));
        System.out.println("ATTACHMENT_EVAL "+split+" gradedRows="+rows.size()+" configurations="+configs.size());
    }
    void fixtures() throws Exception {
        Path fixtures=directory.resolve("fixtures"); Files.createDirectories(fixtures);
        try(var doc=new XWPFDocument();var out=Files.newOutputStream(fixtures.resolve("table.docx"))) {
            doc.createParagraph().createRun().setText("合成付款清单"); var table=doc.createTable(2,2);
            table.getRow(0).getCell(0).setText("项目");table.getRow(0).getCell(1).setText("付款条件");
            table.getRow(1).getCell(0).setText("培训款1357元");table.getRow(1).getCell(1).setText("培训签到表确认后支付"); doc.write(out);
        }
        try(var doc=new PDDocument()) {
            for(int n=1;n<=31;n++) { var page=new PDPage();doc.addPage(page);try(var out=new PDPageContentStream(doc,page)){
                out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),11);out.newLineAtOffset(30,650);
                out.showText("Synthetic document only. Page "+n+". PAYMENT 2468. RELEASE 7 DAYS. EXCEPT UNPAID COSTS.");out.endText();}}
            doc.save(fixtures.resolve("tail31.pdf").toFile());
        }
        String reference="SYNTHETIC DOCUMENT ONLY\nPAYMENT 2468\nRELEASE 7 DAYS AFTER ACCEPTANCE\nEXCEPT UNPAID COSTS\nRECEIPT REQUIRED";
        BufferedImage clear=new BufferedImage(1200,460,BufferedImage.TYPE_INT_RGB);var g=clear.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,1200,460);g.setColor(Color.BLACK);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,34));
        int y=65;for(String line:reference.split("\n")){g.drawString(line,40,y);y+=75;}g.dispose();
        ImageIO.write(clear,"png",fixtures.resolve("scan-clear.png").toFile());
        float[] weights=new float[25];Arrays.fill(weights,1f/25);var blurred=new ConvolveOp(new Kernel(5,5,weights),ConvolveOp.EDGE_NO_OP,null).filter(clear,null);
        ImageIO.write(blurred,"png",fixtures.resolve("scan-blur.png").toFile());
        List<Map<String,Object>> ocr=new ArrayList<>();
        for(String name:List.of("scan-clear.png","scan-blur.png")) {
            String image=Base64.getEncoder().encodeToString(Files.readAllBytes(fixtures.resolve(name)));
            ocr.add(Map.of("key",hash(CHAT+"\n"+image),"name",name,"image",image));
        }
        write("ocr-jobs.json",jobs("ocr",ocr)); write("ocr-reference.json",Map.of("reference",reference));
    }
    static String normalize(String text) { return text.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT); }
    static int editDistance(String a,String b) {
        int[] prev=IntStream.rangeClosed(0,b.length()).toArray();
        for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;
            for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(prev[j]+1,next[j-1]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}
        return prev[b.length()];
    }
    void parse() throws Exception {
        var ocr=cache("ocr",false);var vision=mock(ImageCaptionService.class);
        when(vision.transcribe(any(),anyString())).thenAnswer(call->{String image=Base64.getEncoder().encodeToString(call.getArgument(0));
            var result=Objects.requireNonNull(ocr.get(hash(CHAT+"\n"+image)),"OCR cache missing");
            if(result.has("error"))throw new IllegalStateException("Recorded OCR error");return result.get("text").asText();});
        var parser=new AttachmentParser(new FileProperties(),vision);List<Map<String,Object>> results=new ArrayList<>();
        for(String filename:List.of("table.docx","tail31.pdf","scan-clear.png","scan-blur.png")) {
            long start=System.nanoTime();var parsed=parser.parse(directory.resolve("fixtures").resolve(filename),filename.substring(filename.lastIndexOf('.')+1));
            Map<String,Object> row=new LinkedHashMap<>();row.put("file",filename);row.put("processedPages",parsed.processedPages());row.put("totalPages",parsed.totalPages());
            row.put("failedPages",parsed.failedPages());row.put("warnings",parsed.warnings());row.put("text",parsed.text());row.put("parseReplayMs",(System.nanoTime()-start)/1_000_000.0);
            List<String> quotes=filename.endsWith("docx")?List.of("培训款1357元","培训签到表确认后支付"):filename.endsWith("pdf")?List.of("Page 31.","PAYMENT 2468", "EXCEPT UNPAID COSTS"):List.of("2468","7 DAYS","EXCEPT UNPAID COSTS","RECEIPT REQUIRED");
            row.put("requiredFacts",quotes.size());row.put("extractedFacts",quotes.stream().filter(q->normalize(parsed.text()).contains(normalize(q))).count());
            if(filename.endsWith("png")){String reference=JSON.readTree(directory.resolve("ocr-reference.json").toFile()).get("reference").asText();
                row.put("normalizedCharacterErrorRate",(double)editDistance(normalize(reference),normalize(parsed.text()))/normalize(reference).length());}
            results.add(row);
        }
        write("parsing-results.json",Map.of("rows",results,"note","Real PDF/DOCX parsers; image parser with cached real VL transcription; normalized CER is limited to two synthetic English images"));
        System.out.println("ATTACHMENT_EVAL parser fixtures="+results.size());
    }
    void scoreGeneration() throws Exception {
        var responses=cache("generation",false);var jobs=JSON.readTree(directory.resolve("generation-jobs.json").toFile()).get("items");
        Map<String,JsonNode> questions=new HashMap<>();dataset.get("questions").forEach(q->questions.put(q.get("id").asText(),q));
        var holdout=JSON.readTree(directory.resolve("holdout-results.json").toFile()).get("rows");List<Map<String,Object>> rows=new ArrayList<>();
        for(JsonNode job:jobs){var result=Objects.requireNonNull(responses.get(job.get("key").asText()),"Generation cache missing");var q=questions.get(job.get("id").asText());
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",job.get("id").asText());row.put("config",job.get("config").asText());row.put("modelResult",result);
            boolean answerable=q.get("evidence").size()>0;row.put("answerable",answerable);boolean parsed=false,abstained=false,citationsValid=false,allFacts=false,citationEvidenceComplete=false;
            try{String raw=result.path("text").asText().strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");JsonNode answer=JSON.readTree(raw);
                if(!answer.path("answer").isTextual()||!answer.path("abstain").isBoolean()||!answer.path("citations").isArray())throw new IllegalArgumentException();
                parsed=true;abstained=answer.get("abstain").asBoolean();String text=answer.get("answer").asText();
                allFacts=answerable&&strings(q.get("patterns")).stream().allMatch(pattern->java.util.regex.Pattern.compile(pattern).matcher(text).find());
                List<String> cited=strings(answer.get("citations"));citationsValid=cited.stream().allMatch(strings(job.get("sourceLabels"))::contains);
                if(answerable&&cited.isEmpty())citationsValid=false;
                JsonNode original=null;for(JsonNode candidate:holdout)if(candidate.get("id").equals(job.get("id"))&&candidate.get("config").equals(job.get("config"))){original=candidate;break;}
                assertNotNull(original);var context=JSON.treeToValue(original.get("context"),AttachmentContext.class);
                var citedContext=new AttachmentContext(context.text(),context.sources().stream().filter(s->cited.contains(s.label())).toList(),List.of(),0,0,false);
                citationEvidenceComplete=AttachmentQualityMetrics.score(citedContext,strings(q.get("evidence")).stream().map(evidence::get).toList(),documents).allRequired();
                row.put("answer",answer);
            }catch(Exception ignored){ /* invalid responses remain failures in the denominator */ }
            row.put("jsonValid",parsed);row.put("abstained",abstained);row.put("allFactPatterns",allFacts);row.put("citationLabelsValid",citationsValid);row.put("allEvidenceInCitedSources",citationEvidenceComplete);
            boolean explicitUnknown = row.containsKey("answer") && java.util.regex.Pattern.compile("无法确定|不能确定|未提供|不足以|无法根据|不能根据|缺少|无法从")
                    .matcher(((JsonNode)row.get("answer")).path("answer").asText()).find();
            row.put("noAnswerCorrect",!answerable&&parsed&&abstained&&explicitUnknown);rows.add(row);
        }
        write("generation-results.json",Map.of("rows",rows,"note","Rule-scored fact patterns and cited evidence coverage, not a semantic faithfulness evaluator; no independent human legal review"));
        System.out.println("ATTACHMENT_EVAL generated responses scored="+rows.size());
    }
}
