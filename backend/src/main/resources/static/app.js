const state = {
    versions: [],
    activeVersion: "V3_GUARDED",
    currentResult: null,
    overview: null,
    documents: [],
    allChunks: [],
    filteredChunks: [],
    pipeline: [],
    evaluation: null,
    selectedDocumentId: null,
    selectedChunk: null,
    compareResult: null,
    busy: false,
    demoRunning: false,
    activeView: "overview",
    searchTimer: null
};

const demoQuestion = "高血压肝阳上亢证常见治法和方药是什么？";

const elements = {
    mainNav: document.getElementById("mainNav"),
    pageIndex: document.getElementById("pageIndex"),
    pageTitle: document.getElementById("pageTitle"),
    pageSubtitle: document.getElementById("pageSubtitle"),
    serviceState: document.getElementById("serviceState"),
    serviceText: document.getElementById("serviceText"),
    demoButton: document.getElementById("demoButton"),
    overviewDemoButton: document.getElementById("overviewDemoButton"),
    globalEvaluateButton: document.getElementById("globalEvaluateButton"),
    navChunkCount: document.getElementById("navChunkCount"),
    navCaseCount: document.getElementById("navCaseCount"),
    sidebarChunkCount: document.getElementById("sidebarChunkCount"),
    sidebarDocumentCount: document.getElementById("sidebarDocumentCount"),

    overviewDocumentCount: document.getElementById("overviewDocumentCount"),
    overviewChunkCount: document.getElementById("overviewChunkCount"),
    overviewDiseaseCount: document.getElementById("overviewDiseaseCount"),
    overviewHighEvidenceCount: document.getElementById("overviewHighEvidenceCount"),
    overviewChunkLength: document.getElementById("overviewChunkLength"),
    overviewDataState: document.getElementById("overviewDataState"),
    diseaseDistribution: document.getElementById("diseaseDistribution"),
    overviewSourceDistribution: document.getElementById("overviewSourceDistribution"),
    evidenceRing: document.getElementById("evidenceRing"),
    evidenceARatio: document.getElementById("evidenceARatio"),
    evidenceDistribution: document.getElementById("evidenceDistribution"),
    overviewHighEvidenceRatio: document.getElementById("overviewHighEvidenceRatio"),
    overviewSourceTypeCount: document.getElementById("overviewSourceTypeCount"),
    overviewPendingChunkCount: document.getElementById("overviewPendingChunkCount"),
    overviewStatusText: document.getElementById("overviewStatusText"),
    overviewPipeline: document.getElementById("overviewPipeline"),
    pipelineReadyBadge: document.getElementById("pipelineReadyBadge"),
    evaluationGlance: document.getElementById("evaluationGlance"),
    overviewVersionLadder: document.getElementById("overviewVersionLadder"),
    evaluationTimestamp: document.getElementById("evaluationTimestamp"),

    knowledgeSearch: document.getElementById("knowledgeSearch"),
    knowledgeDisease: document.getElementById("knowledgeDisease"),
    knowledgeSource: document.getElementById("knowledgeSource"),
    knowledgeEvidence: document.getElementById("knowledgeEvidence"),
    resetKnowledgeFilters: document.getElementById("resetKnowledgeFilters"),
    documentSummary: document.getElementById("documentSummary"),
    documentList: document.getElementById("documentList"),
    chunkSummary: document.getElementById("chunkSummary"),
    chunkResultCount: document.getElementById("chunkResultCount"),
    chunkList: document.getElementById("chunkList"),
    chunkDetailBoard: document.getElementById("chunkDetailBoard"),
    chunkDetailId: document.getElementById("chunkDetailId"),
    chunkDetail: document.getElementById("chunkDetail"),
    closeChunkDetail: document.getElementById("closeChunkDetail"),

    versionRail: document.getElementById("versionRail"),
    questionInput: document.getElementById("questionInput"),
    characterCount: document.getElementById("characterCount"),
    askButton: document.getElementById("askButton"),
    compareButton: document.getElementById("compareButton"),
    closeCompareButton: document.getElementById("closeCompareButton"),
    compareSection: document.getElementById("compareSection"),
    compareQuestion: document.getElementById("compareQuestion"),
    compareGrid: document.getElementById("compareGrid"),
    workbenchDisease: document.getElementById("workbenchDisease"),
    workbenchSource: document.getElementById("workbenchSource"),
    workbenchEvidence: document.getElementById("workbenchEvidence"),
    scopeReadout: document.getElementById("scopeReadout"),
    methodNote: document.getElementById("methodNote"),
    answerStatus: document.getElementById("answerStatus"),
    requestIdValue: document.getElementById("requestIdValue"),
    copyAnswerButton: document.getElementById("copyAnswerButton"),
    answerStage: document.getElementById("answerStage"),
    confidenceValue: document.getElementById("confidenceValue"),
    latencyValue: document.getElementById("latencyValue"),
    candidateValue: document.getElementById("candidateValue"),
    selectedValue: document.getElementById("selectedValue"),
    tracePath: document.getElementById("tracePath"),
    traceList: document.getElementById("traceList"),
    safetyStatus: document.getElementById("safetyStatus"),
    safetyTitle: document.getElementById("safetyTitle"),
    safetyDescription: document.getElementById("safetyDescription"),
    safetyScope: document.getElementById("safetyScope"),
    strategyValue: document.getElementById("strategyValue"),
    citationCountValue: document.getElementById("citationCountValue"),

    evaluationRunButton: document.getElementById("evaluationRunButton"),
    datasetName: document.getElementById("datasetName"),
    datasetSize: document.getElementById("datasetSize"),
    versionSize: document.getElementById("versionSize"),
    evaluationStatus: document.getElementById("evaluationStatus"),
    evaluationRunTime: document.getElementById("evaluationRunTime"),
    evaluationInsight: document.getElementById("evaluationInsight"),
    evaluationSummary: document.getElementById("evaluationSummary"),
    metricMatrix: document.getElementById("metricMatrix"),
    failureVersionFilter: document.getElementById("failureVersionFilter"),
    failureSummary: document.getElementById("failureSummary"),
    caseResultCount: document.getElementById("caseResultCount"),
    evaluationCaseRows: document.getElementById("evaluationCaseRows"),

    pipelineStageBadge: document.getElementById("pipelineStageBadge"),
    pipelineStages: document.getElementById("pipelineStages"),
    importForm: document.getElementById("importForm"),
    importButton: document.getElementById("importButton"),
    loadImportSampleButton: document.getElementById("loadImportSampleButton"),
    importHint: document.getElementById("importHint"),
    registrySummary: document.getElementById("registrySummary"),
    registryList: document.getElementById("registryList"),
    toast: document.getElementById("toast")
};

document.addEventListener("DOMContentLoaded", initialize);
window.addEventListener("hashchange", () => setView(window.location.hash.replace("#", "") || "overview"));

async function initialize() {
    bindEvents();
    updateCharacterCount();
    updateScopeReadout();
    setView(window.location.hash.replace("#", "") || "overview");

    await Promise.allSettled([
        loadVersions(),
        loadKnowledgeData(),
        checkHealth()
    ]);

    renderOverview();
    populateFilterOptions();
    renderDocuments();
    renderChunks(state.allChunks);
    renderPipeline();
    renderRegistry();
    await runEvaluation({silent: true});
}

function bindEvents() {
    elements.mainNav.addEventListener("click", event => {
        const button = event.target.closest("[data-view]");
        if (button) {
            setView(button.dataset.view);
        }
    });

    document.addEventListener("click", event => {
        const goView = event.target.closest("[data-go-view]");
        if (goView) {
            setView(goView.dataset.goView);
        }
    });

    elements.globalEvaluateButton.addEventListener("click", async () => {
        setView("evaluation");
        await runEvaluation();
    });
    elements.demoButton.addEventListener("click", runDemoSequence);
    elements.overviewDemoButton.addEventListener("click", runDemoSequence);
    elements.evaluationRunButton.addEventListener("click", () => runEvaluation());
    elements.askButton.addEventListener("click", askQuestion);
    elements.compareButton.addEventListener("click", compareVersions);
    elements.copyAnswerButton.addEventListener("click", copyCurrentAnswer);
    elements.closeCompareButton.addEventListener("click", () => elements.compareSection.classList.add("hidden"));
    elements.closeChunkDetail.addEventListener("click", closeChunkDetail);
    elements.resetKnowledgeFilters.addEventListener("click", resetKnowledgeFilters);

    elements.questionInput.addEventListener("input", updateCharacterCount);
    elements.questionInput.addEventListener("keydown", event => {
        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
            askQuestion();
        }
    });

    [elements.workbenchDisease, elements.workbenchSource, elements.workbenchEvidence].forEach(select => {
        select.addEventListener("change", updateScopeReadout);
    });

    document.addEventListener("click", event => {
        const preset = event.target.closest("[data-question]");
        if (!preset) {
            return;
        }
        elements.questionInput.value = preset.dataset.question;
        updateCharacterCount();
        elements.questionInput.focus();
        showToast("示例问题已载入，点击“运行检索”查看证据链");
    });

    elements.knowledgeSearch.addEventListener("input", () => {
        window.clearTimeout(state.searchTimer);
        state.searchTimer = window.setTimeout(loadFilteredChunks, 260);
    });
    [elements.knowledgeDisease, elements.knowledgeSource, elements.knowledgeEvidence].forEach(select => {
        select.addEventListener("change", loadFilteredChunks);
    });

    elements.documentList.addEventListener("click", event => {
        const item = event.target.closest("[data-document-id]");
        if (item) {
            selectDocument(item.dataset.documentId);
        }
    });

    elements.chunkList.addEventListener("click", event => {
        const item = event.target.closest("[data-chunk-id]");
        if (item) {
            openChunk(item.dataset.chunkId);
        }
    });

    elements.failureVersionFilter.addEventListener("change", () => renderEvaluationResults());
    elements.importForm.addEventListener("submit", importDocument);
    elements.loadImportSampleButton.addEventListener("click", fillImportSample);
}

async function loadVersions() {
    try {
        state.versions = await request("/api/v1/versions");
        if (!state.versions.some(version => version.version === state.activeVersion)) {
            state.activeVersion = state.versions.at(-1)?.version || "";
        }
        renderVersionRail();
        renderComparisonOptions();
    } catch (error) {
        showToast(error.message, true);
    }
}

async function loadKnowledgeData() {
    try {
        const [overview, chunks, documents, pipeline] = await Promise.all([
            request("/api/v1/knowledge/overview"),
            request("/api/v1/knowledge/chunks"),
            request("/api/v1/knowledge/documents"),
            request("/api/v1/knowledge/pipeline")
        ]);
        state.overview = overview;
        state.allChunks = chunks;
        state.filteredChunks = chunks;
        state.documents = documents;
        state.pipeline = pipeline;
        updateKnowledgeCounts();
    } catch (error) {
        showToast(error.message, true);
    }
}

async function refreshKnowledgeData() {
    await loadKnowledgeData();
    renderOverview();
    populateFilterOptions();
    renderDocuments();
    renderChunks(state.filteredChunks);
    renderPipeline();
    renderRegistry();
}

async function checkHealth() {
    try {
        const response = await fetch("/actuator/health", {headers: {"Accept": "application/json"}});
        const payload = await response.json();
        if (!response.ok || payload.status !== "UP") {
            throw new Error("health check failed");
        }
        elements.serviceState.classList.add("online");
        elements.serviceState.classList.remove("offline");
        elements.serviceText.textContent = "离线演示服务在线";
    } catch (error) {
        elements.serviceState.classList.add("offline");
        elements.serviceState.classList.remove("online");
        elements.serviceText.textContent = "服务离线";
    }
}

function setView(view) {
    const target = document.getElementById(`view-${view}`);
    if (!target) {
        return;
    }
    state.activeView = view;
    document.querySelectorAll(".view").forEach(section => section.classList.toggle("active", section.id === `view-${view}`));
    elements.mainNav.querySelectorAll("[data-view]").forEach(button => {
        button.classList.toggle("active", button.dataset.view === view);
    });
    elements.pageIndex.textContent = target.dataset.index;
    elements.pageTitle.textContent = target.dataset.title;
    elements.pageSubtitle.textContent = target.dataset.subtitle;
    if (window.location.hash !== `#${view}`) {
        history.replaceState(null, "", `#${view}`);
    }
    window.scrollTo({top: 0, behavior: "smooth"});
}

function updateKnowledgeCounts() {
    const chunkCount = state.overview?.chunkCount ?? state.allChunks.length;
    const documentCount = state.overview?.documentCount ?? state.documents.length;
    elements.navChunkCount.textContent = String(chunkCount);
    elements.sidebarChunkCount.textContent = String(chunkCount);
    elements.sidebarDocumentCount.textContent = String(documentCount);
}

function renderVersionRail() {
    elements.versionRail.replaceChildren();
    state.versions.forEach(version => {
        const button = node("button", "version-option");
        button.type = "button";
        button.dataset.version = version.version;
        button.classList.toggle("active", version.version === state.activeVersion);

        const code = node("span", "version-code", version.code);
        const name = node("strong", "", version.name);
        const description = node("p", "", version.description);
        const flags = node("div", "version-flags");
        flags.append(node("span", "", version.retrievalFeature), node("span", "", `${version.reliability}可信`));
        button.append(code, name, description, flags);
        button.addEventListener("click", () => {
            state.activeVersion = version.version;
            renderVersionRail();
            updateMethodNote();
        });
        elements.versionRail.append(button);
    });
    updateMethodNote();
}

function updateMethodNote() {
    const version = getActiveVersion();
    if (!version) {
        return;
    }
    elements.methodNote.replaceChildren();
    elements.methodNote.append(
        node("span", "", version.code),
        node("p", "", `${version.description} 检索：${version.retrievalFeature}；引用：${version.citationFeature}。`)
    );
}

function renderComparisonOptions() {
    elements.failureVersionFilter.replaceChildren();
    elements.failureVersionFilter.append(new Option("全部版本", ""));
    state.versions.forEach(version => {
        elements.failureVersionFilter.append(new Option(`${version.code} ${version.name}`, version.version));
    });
}

function renderOverview() {
    const overview = state.overview;
    if (!overview) {
        return;
    }
    elements.overviewDocumentCount.textContent = formatInteger(overview.documentCount);
    elements.overviewChunkCount.textContent = formatInteger(overview.chunkCount);
    elements.overviewDiseaseCount.textContent = formatInteger(overview.diseaseCount);
    elements.overviewHighEvidenceCount.textContent = formatInteger(overview.highEvidenceChunkCount);
    elements.overviewChunkLength.textContent = formatDecimal(overview.averageChunkLength, 0);
    elements.overviewDataState.textContent = `已建立 ${formatInteger(overview.documentCount)} 份演示文档与 ${formatInteger(overview.chunkCount)} 个可追溯片段，未接入真实患者数据。`;
    renderDistribution(elements.diseaseDistribution, overview.diseaseDistribution);
    renderSourceDistribution(overview.sourceDistribution);
    renderEvidence(overview);
    renderPipeline();
}

function renderDistribution(container, items) {
    container.replaceChildren();
    if (!items?.length) {
        container.append(emptyCompact("暂无分布数据", "知识片段加载后会显示统计结果。"));
        return;
    }
    items.forEach(item => {
        const row = node("div", "distribution-row");
        const label = node("span", "distribution-label", item.label);
        const track = node("div", "distribution-track");
        const fill = node("div", "distribution-fill");
        fill.style.width = `${clamp(item.ratio, 0, 1) * 100}%`;
        track.append(fill);
        const value = node("span", "distribution-value", `${item.value} · ${formatPercent(item.ratio, 0)}`);
        row.append(label, track, value);
        container.append(row);
    });
}

function renderSourceDistribution(items) {
    elements.overviewSourceDistribution.replaceChildren();
    if (!items?.length) {
        elements.overviewSourceDistribution.append(emptyCompact("暂无来源数据", "登记文档后会形成来源结构。"));
        return;
    }
    const maxValue = Math.max(...items.map(item => Number(item.value || 0)), 1);
    items.slice(0, 6).forEach((item, index) => {
        const row = node("div", "source-row");
        const head = node("div", "source-row-head");
        head.append(
            node("span", "", item.label),
            node("strong", "", `${item.value} 片 · ${formatPercent(item.ratio, 0)}`)
        );
        const track = node("div", "source-track");
        const fill = node("div", `source-fill tone-${index % 3}`);
        fill.style.width = `${clamp(Number(item.value || 0) / maxValue, 0, 1) * 100}%`;
        track.append(fill);
        row.append(head, track);
        elements.overviewSourceDistribution.append(row);
    });
}

function renderEvidence(overview) {
    const ratio = overview.chunkCount
        ? overview.highEvidenceChunkCount / overview.chunkCount
        : 0;
    elements.evidenceRing.style.setProperty("--value", `${ratio * 100}%`);
    elements.evidenceARatio.textContent = formatPercent(ratio, 0);
    elements.evidenceDistribution.replaceChildren();
    overview.evidenceDistribution.forEach(item => {
        const row = node("div", "compact-row");
        row.append(
            node("span", "", `${item.label} 级证据`),
            node("strong", "", `${item.value} / ${formatPercent(item.ratio, 0)}`)
        );
        elements.evidenceDistribution.append(row);
    });
    elements.overviewHighEvidenceRatio.textContent = formatPercent(ratio, 0);
    elements.overviewSourceTypeCount.textContent = `${overview.sourceTypeCount} 类`;
    elements.overviewPendingChunkCount.textContent = `${overview.pendingChunkCount} 片`;
    const active = overview.statusDistribution.find(item => item.label === "ACTIVE");
    elements.overviewStatusText.textContent = active
        ? `${active.value} 个片段可用于检索`
        : "状态待检查";
}

function renderPipeline() {
    if (!state.pipeline.length) {
        return;
    }
    renderPipelineStrip(elements.overviewPipeline, true);
    renderPipelineStrip(elements.pipelineStages, false);
    const readyCount = state.pipeline.filter(stage => stage.status === "READY").length;
    elements.pipelineReadyBadge.textContent = `${readyCount} / ${state.pipeline.length} READY`;
    elements.pipelineStageBadge.textContent = `${readyCount} / ${state.pipeline.length} READY`;
}

function renderPipelineStrip(container, compact) {
    container.replaceChildren();
    state.pipeline.forEach((stage, index) => {
        const statusClass = stage.status === "READY"
            ? "ready"
            : stage.status === "PARTIAL" ? "partial" : "";
        const item = node("div", `pipeline-stage ${statusClass}`.trim());
        item.append(node("span", "pipeline-node", String(index + 1).padStart(2, "0")));
        item.append(node("strong", "", stage.name));
        if (!compact) {
            item.append(node("p", "", stage.description));
        } else {
            item.append(node("p", "", stage.description.replace(/^将|构建|检查/, "")));
        }
        item.append(node("small", "", `${stage.itemCount} 项 · ${formatPipelineStatus(stage.status)}`));
        container.append(item);
    });
}

function renderDocuments() {
    elements.documentList.replaceChildren();
    elements.documentSummary.textContent = `${state.documents.length} 份来源已登记`;
    if (!state.documents.length) {
        elements.documentList.append(emptyCompact("尚无文档", "请先通过数据流水线登记来源。"));
        return;
    }
    state.documents.forEach((document, index) => {
        const button = node("button", "document-item");
        button.type = "button";
        button.dataset.documentId = document.id;
        button.classList.toggle("active", document.id === state.selectedDocumentId);
        button.append(node("span", "document-index", String(index + 1).padStart(2, "0")));

        const copy = node("span", "document-copy");
        copy.append(node("strong", "", document.title));
        const meta = node("span", "document-meta");
        meta.append(
            node("span", "", document.sourceType),
            node("span", "", String(document.publicationYear)),
            node("span", "", `证据 ${document.evidenceLevel}`)
        );
        copy.append(meta, node("small", "", `${document.publisher} · ${document.status}`));
        button.append(copy, node("span", "document-count", `${document.chunkCount} 片`));
        elements.documentList.append(button);
    });
}

function renderChunks(chunks) {
    state.filteredChunks = chunks;
    elements.chunkList.replaceChildren();
    elements.chunkResultCount.textContent = `${chunks.length} 条`;
    elements.chunkSummary.textContent = state.selectedDocumentId
        ? `来源 ${state.selectedDocumentId} 的片段`
        : "按关键词、疾病和证据等级筛选";
    if (!chunks.length) {
        elements.chunkList.append(emptyCompact("没有匹配片段", "调整筛选条件或登记新的来源文档。"));
        return;
    }
    chunks.forEach(chunk => {
        const button = node("button", "chunk-item");
        button.type = "button";
        button.dataset.chunkId = chunk.id;
        button.classList.toggle("active", chunk.id === state.selectedChunk?.id);

        const head = node("span", "chunk-head");
        head.append(node("strong", "", chunk.title), evidenceTag(chunk.evidenceLevel));

        const idLine = node("span", "chunk-id-line");
        idLine.append(
            node("code", "", chunk.id),
            node("span", "", chunk.disease),
            node("span", "", chunk.page),
            node("span", "", chunk.section)
        );

        const sourceLine = node("span", "chunk-source-line");
        sourceLine.append(
            node("span", "", chunk.sourceType),
            node("span", "", `${chunk.publisher} · ${chunk.publicationYear}`)
        );

        const content = node("span", "chunk-content", chunk.content);
        const tags = node("span", "tag-row");
        [
            ...chunk.syndromes,
            ...chunk.formulas,
            ...chunk.keywords.slice(0, 2)
        ].slice(0, 5).forEach(text => tags.append(node("span", "tag", text)));

        button.append(head, idLine, sourceLine, content, tags);
        elements.chunkList.append(button);
    });
}

async function loadFilteredChunks() {
    state.selectedDocumentId = null;
    renderDocuments();
    const params = new URLSearchParams();
    const keyword = elements.knowledgeSearch.value.trim();
    const disease = elements.knowledgeDisease.value;
    const sourceType = elements.knowledgeSource.value;
    const evidenceLevel = elements.knowledgeEvidence.value;
    if (keyword) params.set("keyword", keyword);
    if (disease) params.set("disease", disease);
    if (sourceType) params.set("sourceType", sourceType);
    if (evidenceLevel) params.set("evidenceLevel", evidenceLevel);
    try {
        const chunks = await request(`/api/v1/knowledge/chunks${params.toString() ? `?${params}` : ""}`);
        renderChunks(chunks);
    } catch (error) {
        showToast(error.message, true);
    }
}

function selectDocument(documentId) {
    state.selectedDocumentId = state.selectedDocumentId === documentId ? null : documentId;
    renderDocuments();
    if (!state.selectedDocumentId) {
        renderChunks(state.allChunks);
        return;
    }
    renderChunks(state.allChunks.filter(chunk => chunk.documentId === state.selectedDocumentId));
}

async function openChunk(chunkId) {
    const cached = state.allChunks.find(chunk => chunk.id === chunkId)
        || state.filteredChunks.find(chunk => chunk.id === chunkId);
    try {
        const chunk = cached || await request(`/api/v1/knowledge/chunks/${encodeURIComponent(chunkId)}`);
        state.selectedChunk = chunk;
        renderChunkDetail(chunk);
        renderChunks(state.filteredChunks);
        elements.chunkDetailBoard.classList.remove("hidden");
        elements.chunkDetailBoard.scrollIntoView({behavior: "smooth", block: "start"});
    } catch (error) {
        showToast(error.message, true);
    }
}

function renderChunkDetail(chunk) {
    elements.chunkDetailId.textContent = `${chunk.id} · ${chunk.documentId}`;
    elements.chunkDetail.replaceChildren();
    const content = node("div", "detail-content");
    content.append(node("blockquote", "", chunk.content));
    const detailTags = node("div", "detail-tags");
    detailTags.append(
        tagGroup("证型", chunk.syndromes),
        tagGroup("方剂", chunk.formulas),
        tagGroup("中药", chunk.herbs),
        tagGroup("关键词", chunk.keywords)
    );
    content.append(detailTags);

    const facts = node("dl", "detail-facts");
    [
        ["来源类型", chunk.sourceType],
        ["发布机构", `${chunk.publisher} · ${chunk.publicationYear}`],
        ["疾病范围", chunk.disease],
        ["章节", chunk.section],
        ["引用位置", chunk.page],
        ["证据等级", `${chunk.evidenceLevel} 级`],
        ["文档版本", chunk.version],
        ["校验值", chunk.checksum],
        ["入库时间", formatDateTime(chunk.ingestedAt)],
        ["状态", chunk.status]
    ].forEach(([label, value]) => {
        const row = node("div");
        row.append(node("span", "", label), node("strong", "", String(value || "--")));
        facts.append(row);
    });
    elements.chunkDetail.append(content, facts);
}

function closeChunkDetail() {
    state.selectedChunk = null;
    elements.chunkDetailBoard.classList.add("hidden");
    renderChunks(state.filteredChunks);
}

function populateFilterOptions() {
    const diseases = unique([
        ...state.allChunks.map(chunk => chunk.disease),
        ...state.documents.flatMap(document => document.diseaseScope)
    ]);
    const sources = unique([
        ...state.allChunks.map(chunk => chunk.sourceType),
        ...state.documents.map(document => document.sourceType)
    ]);
    const evidenceLevels = unique(state.allChunks.map(chunk => chunk.evidenceLevel))
        .sort((left, right) => left.localeCompare(right));

    fillSelect(elements.knowledgeDisease, diseases, "全部疾病", elements.knowledgeDisease.value);
    fillSelect(elements.knowledgeSource, sources, "全部来源", elements.knowledgeSource.value);
    fillSelect(elements.knowledgeEvidence, evidenceLevels.map(value => `${value}`), "全部等级", elements.knowledgeEvidence.value);
    fillSelect(elements.workbenchDisease, diseases, "全部疾病", elements.workbenchDisease.value);
    fillSelect(elements.workbenchSource, sources, "全部来源", elements.workbenchSource.value);
    updateKnowledgeCounts();
}

function fillSelect(select, values, placeholder, currentValue) {
    select.replaceChildren(new Option(placeholder, ""));
    values.forEach(value => {
        if (value !== undefined && value !== null && String(value).trim()) {
            select.append(new Option(String(value), String(value)));
        }
    });
    select.value = currentValue || "";
}

function resetKnowledgeFilters() {
    elements.knowledgeSearch.value = "";
    elements.knowledgeDisease.value = "";
    elements.knowledgeSource.value = "";
    elements.knowledgeEvidence.value = "";
    state.selectedDocumentId = null;
    renderDocuments();
    renderChunks(state.allChunks);
}

function updateCharacterCount() {
    elements.characterCount.textContent = String(elements.questionInput.value.length);
}

function updateScopeReadout() {
    const parts = [
        elements.workbenchDisease.value,
        elements.workbenchSource.value,
        elements.workbenchEvidence.value ? `${elements.workbenchEvidence.value} 级证据` : ""
    ].filter(Boolean);
    const scope = parts.length ? parts.join(" / ") : "全库";
    elements.scopeReadout.textContent = scope;
    elements.safetyScope.textContent = scope;
}

async function askQuestion() {
    const question = elements.questionInput.value.trim();
    if (!question) {
        showToast("请输入需要检索的问题", true);
        elements.questionInput.focus();
        return;
    }
    if (!state.activeVersion) {
        showToast("迭代版本尚未加载", true);
        return;
    }
    setBusy(true);
    elements.answerStatus.textContent = "正在执行检索与证据生成";
    renderLoading("正在检索知识库并校验证据");
    try {
        state.currentResult = await request("/api/v1/rag/chat", {
            method: "POST",
            body: JSON.stringify({
                question,
                version: state.activeVersion,
                disease: elements.workbenchDisease.value || null,
                sourceType: elements.workbenchSource.value || null,
                evidenceLevel: elements.workbenchEvidence.value || null
            })
        });
        renderAnswer(state.currentResult);
        renderTelemetry(state.currentResult);
    } catch (error) {
        renderError(error.message);
        resetTelemetry();
        showToast(error.message, true);
    } finally {
        setBusy(false);
    }
}

function renderLoading(message) {
    const wrapper = node("div", "loading-state");
    const head = node("div", "loading-head");
    head.append(node("span", "loading-ring"), node("strong", "", message));
    const stages = node("div", "loading-stage-map");
    ["问题约束", "混合召回", "证据校验", "生成回答"].forEach((label, index) => {
        const stage = node("span");
        stage.style.animationDelay = `${index * 160}ms`;
        stage.append(node("b", "", String(index + 1).padStart(2, "0")), document.createTextNode(label));
        stages.append(stage);
    });
    wrapper.append(head, stages);
    elements.answerStage.replaceChildren(wrapper);
}

function renderError(message) {
    const wrapper = node("div", "error-state");
    wrapper.append(node("strong", "", "请求未完成"), node("span", "", message));
    elements.answerStage.replaceChildren(wrapper);
}

function renderAnswer(result) {
    const version = state.versions.find(item => item.version === result.version);
    elements.answerStatus.textContent = `已完成 ${version?.code || result.version} 检索与生成`;
    elements.requestIdValue.textContent = result.requestId.slice(0, 8).toUpperCase();
    elements.copyAnswerButton.classList.remove("hidden");
    elements.answerStage.replaceChildren();

    const card = node("div", "answer-card");
    const meta = node("div", "answer-meta");
    meta.append(
        node("strong", "", `${version?.code || result.version} · ${version?.name || "迭代版本"}`),
        node("span", "", formatDateTime(result.generatedAt))
    );
    const evidenceLevels = unique(result.citations.map(citation => citation.evidenceLevel));
    const summary = node("div", "answer-summary");
    [
        ["检索策略", version?.retrievalFeature || "--"],
        ["引用证据", `${result.citations.length} 条`],
        ["证据等级", evidenceLevels.length ? evidenceLevels.join(" / ") : "未命中"],
        ["安全状态", result.refused ? "已拒答" : result.retrieval.sufficientEvidence ? "阈值通过" : "证据不足"]
    ].forEach(([label, value]) => {
        const item = node("span");
        item.append(node("small", "", label), node("strong", "", value));
        summary.append(item);
    });
    card.append(meta, summary);
    if (result.refused) {
        card.append(refusalBanner(result.refusalReason));
    }

    const answer = node("div", "answer-copy");
    result.answer.split(/\n{2,}/).filter(Boolean).forEach(paragraph => {
        answer.append(node("p", "", paragraph));
    });
    card.append(answer);
    if (result.citations.length) {
        card.append(citationSection(result.citations));
    }
    elements.answerStage.append(card);
}

async function copyCurrentAnswer() {
    if (!state.currentResult?.answer) {
        showToast("暂无可复制的回答", true);
        return;
    }
    try {
        await navigator.clipboard.writeText(state.currentResult.answer);
        showToast("回答正文已复制");
    } catch (error) {
        showToast("浏览器未允许复制，请手动选择回答正文", true);
    }
}

function refusalBanner(reason) {
    const banner = node("div", "refusal-banner");
    banner.innerHTML = `
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
            <path d="M12 8v5"></path>
            <path d="M12 17h.01"></path>
        </svg>
    `;
    const copy = node("div");
    copy.append(node("strong", "", "已触发安全拒答"), node("p", "", reason || "当前问题超出系统安全边界。"));
    banner.append(copy);
    return banner;
}

function citationSection(citations) {
    const section = node("section", "citation-section");
    section.append(node("h3", "", `引用证据 · ${citations.length}`));
    const list = node("div", "citation-list");
    citations.forEach((citation, index) => {
        const item = node("article", "citation-item");
        const head = node("div", "citation-head");
        head.append(
            node("strong", "", `${index + 1}. ${citation.documentTitle}`),
            node("span", "", `R ${formatDecimal(citation.relevanceScore, 3)}`)
        );
        const meta = node("div", "citation-meta");
        meta.append(
            node("span", "", citation.chunkId),
            node("span", "", citation.sourceType),
            node("span", "", citation.page),
            node("span", "", `证据 ${citation.evidenceLevel}`)
        );
        item.append(head, meta, node("p", "citation-quote", citation.quote));
        item.addEventListener("click", () => openCitation(citation.chunkId));
        list.append(item);
    });
    section.append(list);
    return section;
}

async function openCitation(chunkId) {
    setView("knowledge");
    await openChunk(chunkId);
}

function renderTelemetry(result) {
    const confidence = clamp(result.confidence, 0, 1);
    elements.confidenceValue.textContent = formatPercent(confidence, 0);
    elements.latencyValue.textContent = `${result.latencyMs} ms`;
    elements.candidateValue.textContent = String(
        result.retrieval.lexicalCandidateCount + result.retrieval.vectorCandidateCount
    );
    elements.selectedValue.textContent = String(result.retrieval.selectedCount);
    elements.strategyValue.textContent = result.retrieval.strategy;
    elements.citationCountValue.textContent = String(result.citations.length);
    updateScopeReadout();

    const traceSteps = [
        ["词法召回", String(result.retrieval.lexicalCandidateCount)],
        ["向量召回", String(result.retrieval.vectorCandidateCount)],
        ["RRF 融合", String(result.retrieval.fusedCandidateCount)],
        ["最终入选", String(result.retrieval.selectedCount)]
    ];
    elements.tracePath.replaceChildren();
    traceSteps.forEach(([label, value]) => {
        const step = node("div", "trace-step");
        step.append(node("strong", "", value), node("span", "", label));
        elements.tracePath.append(step);
    });

    elements.traceList.replaceChildren();
    [
        `策略：${result.retrieval.strategy}`,
        `证据充分性：${result.retrieval.sufficientEvidence ? "达到阈值" : "低于阈值"}`,
        ...result.retrieval.notes
    ].forEach(text => elements.traceList.append(node("li", "", text)));

    const danger = result.refused || !result.retrieval.sufficientEvidence;
    elements.safetyStatus.classList.toggle("danger", danger);
    elements.safetyTitle.textContent = result.refused
        ? "安全拒答已触发"
        : result.retrieval.sufficientEvidence ? "证据阈值通过" : "证据不足";
    elements.safetyDescription.textContent = result.refused
        ? result.refusalReason
        : result.retrieval.sufficientEvidence
            ? "回答已通过证据充分性检查并附带可追溯引用。"
            : "未达到最低证据阈值，系统不会生成确定结论。";
}

function resetTelemetry() {
    elements.confidenceValue.textContent = "--";
    elements.latencyValue.textContent = "--";
    elements.candidateValue.textContent = "--";
    elements.selectedValue.textContent = "--";
    elements.strategyValue.textContent = "--";
    elements.citationCountValue.textContent = "--";
    elements.tracePath.replaceChildren();
    elements.traceList.replaceChildren(node("li", "", "请求未完成"));
    elements.safetyStatus.classList.add("danger");
    elements.safetyTitle.textContent = "请求失败";
    elements.safetyDescription.textContent = "请检查输入与后端服务状态后重试。";
}

async function compareVersions() {
    const question = elements.questionInput.value.trim();
    if (!question) {
        showToast("请输入需要对比的问题", true);
        return;
    }
    setBusy(true);
    elements.compareSection.classList.remove("hidden");
    elements.compareQuestion.textContent = question;
    elements.compareGrid.replaceChildren(loadingBlock("正在运行四个迭代版本"));
    try {
        state.compareResult = await request("/api/v1/rag/compare", {
            method: "POST",
            body: JSON.stringify({
                question,
                disease: elements.workbenchDisease.value || null,
                sourceType: elements.workbenchSource.value || null,
                evidenceLevel: elements.workbenchEvidence.value || null
            })
        });
        renderComparison(state.compareResult);
        elements.compareSection.scrollIntoView({behavior: "smooth", block: "start"});
    } catch (error) {
        elements.compareGrid.replaceChildren(emptyCompact("对比运行失败", error.message));
        showToast(error.message, true);
    } finally {
        setBusy(false);
    }
}

function renderComparison(result) {
    elements.compareGrid.replaceChildren();
    state.versions.forEach(version => {
        const response = result?.[version.version];
        if (!response) {
            return;
        }
        const article = node("article", "compare-result");
        const head = node("div", "compare-result-head");
        head.append(
            node("strong", "", `${version.code} ${version.name}`),
            node("span", `result-status ${response.refused ? "fail" : "pass"}`, response.refused ? "REFUSED" : "ANSWERED")
        );
        const answer = node("p", "compare-answer", response.answer);
        const stats = node("div", "compare-stats");
        [
            ["置信度", formatPercent(response.confidence, 0)],
            ["耗时", `${response.latencyMs}ms`],
            ["引用", String(response.citations.length)],
            ["入选证据", String(response.retrieval.selectedCount)]
        ].forEach(([label, value]) => {
            const row = node("span");
            row.append(node("em", "", label), node("strong", "", value));
            stats.append(row);
        });
        article.append(head, answer, stats);
        elements.compareGrid.append(article);
    });
}

async function runEvaluation({silent = false} = {}) {
    if (state.busy) {
        return;
    }
    setBusy(true);
    elements.evaluationStatus.textContent = "计算中";
    elements.evaluationRunTime.textContent = "运行中";
    if (!silent) {
        elements.evaluationSummary.replaceChildren(loadingBlock("正在执行 48 条版本级样本"));
    }
    try {
        state.evaluation = await request("/api/v1/evaluations/run", {method: "POST"});
        renderEvaluation();
    } catch (error) {
        elements.evaluationStatus.textContent = "运行失败";
        elements.evaluationRunTime.textContent = "失败";
        showToast(error.message, true);
    } finally {
        setBusy(false);
    }
}

function renderEvaluation() {
    const result = state.evaluation;
    if (!result) {
        return;
    }
    elements.datasetName.textContent = result.datasetName;
    elements.datasetSize.textContent = String(result.totalCases);
    elements.versionSize.textContent = String(result.summaries.length);
    elements.evaluationStatus.textContent = "已完成";
    elements.evaluationRunTime.textContent = formatDateTime(result.generatedAt);
    elements.navCaseCount.textContent = String(result.caseResults.length);
    renderEvaluationInsight(result);
    renderEvaluationSummary(result);
    renderEvaluationResults();
    renderEvaluationGlance(result);
}

function renderEvaluationInsight(result) {
    if (!result?.summaries?.length) {
        return;
    }
    const baseline = result.summaries.find(item => item.version === "V0_DIRECT") || result.summaries[0];
    const current = result.summaries.find(item => item.version === "V3_GUARDED") || result.summaries.at(-1);
    const hitGain = current.hitRateAt3 - baseline.hitRateAt3;
    const citationGain = current.citationCoverage - baseline.citationCoverage;
    const refusalGain = current.refusalAccuracy - baseline.refusalAccuracy;
    const failureReduction = baseline.failureCount - current.failureCount;

    elements.evaluationInsight.replaceChildren();
    const eyebrow = node("span", "", "ITERATION SIGNAL · V0 → V3");
    const copy = node("p");
    copy.append(
        node("strong", "", "证据约束版本形成可量化增益"),
        document.createTextNode(
            `：Hit@3 ${signedPercent(hitGain)}，引用覆盖 ${signedPercent(citationGain)}，` +
            `拒答准确率 ${signedPercent(refusalGain)}，失败样本 ${failureReduction >= 0 ? "减少" : "增加"} ${Math.abs(failureReduction)} 条。`
        )
    );
    const metrics = node("div", "insight-metrics");
    [
        ["Hit@3 增益", signedPercent(hitGain)],
        ["引用覆盖增益", signedPercent(citationGain)],
        ["失败样本变化", `${failureReduction >= 0 ? "-" : "+"}${Math.abs(failureReduction)}`]
    ].forEach(([label, value]) => {
        const metric = node("span");
        metric.append(node("small", "", label), node("strong", "", value));
        metrics.append(metric);
    });
    elements.evaluationInsight.append(eyebrow, copy, metrics);
}

function renderEvaluationSummary(result) {
    const bestVersion = bestEvaluationVersion(result.summaries);
    elements.evaluationSummary.replaceChildren();
    result.summaries.forEach(summary => {
        const version = findVersion(summary.version);
        const block = node("article", `evaluation-version${summary.version === bestVersion ? " best" : ""}`);
        const head = node("div", "evaluation-version-head");
        head.append(
            node("strong", "", `${version?.code || summary.version} ${version?.name || ""}`),
            node("span", "", `${formatDecimal(summary.averageLatencyMs, 1)} ms`)
        );
        block.append(head);
        [
            ["Hit@1", summary.hitRateAt1],
            ["Hit@3", summary.hitRateAt3],
            ["MRR", summary.meanReciprocalRank],
            ["引用覆盖", summary.citationCoverage],
            ["拒答准确", summary.refusalAccuracy]
        ].forEach(([label, value]) => block.append(evaluationMetric(label, value)));
        const failure = node("div", "evaluation-failure");
        failure.append(node("span", "", "失败样本"), node("strong", "", String(summary.failureCount)));
        block.append(failure);
        elements.evaluationSummary.append(block);
    });
}

function renderEvaluationResults() {
    const result = state.evaluation;
    if (!result) {
        return;
    }
    renderMetricMatrix(result.summaries);
    renderFailureSummary(result.caseResults);
    renderCaseRows(result.caseResults);
}

function renderEvaluationGlance(result) {
    elements.overviewVersionLadder.replaceChildren();
    elements.evaluationGlance.querySelector(".empty-compact")?.remove();
    if (!result) {
        return;
    }
    const summary = result.summaries.find(item => item.version === "V3_GUARDED") || result.summaries.at(-1);
    if (!summary) {
        return;
    }
    elements.evaluationTimestamp.textContent = formatTime(result.generatedAt);
    const bestVersion = bestEvaluationVersion(result.summaries);
    result.summaries.forEach(item => {
        const version = findVersion(item.version);
        const step = node("article", `iteration-step${item.version === bestVersion ? " best" : ""}`);
        const head = node("div", "iteration-step-head");
        const identity = node("div", "iteration-identity");
        identity.append(
            node("span", "version-code", version?.code || item.version),
            node("strong", "", version?.name || item.version)
        );
        head.append(
            identity,
            node("span", "iteration-score", formatPercent(item.hitRateAt3, 0))
        );
        const track = node("div", "iteration-track");
        const scoreFill = node("div", "iteration-score-fill");
        scoreFill.style.width = `${clamp(item.hitRateAt3, 0, 1) * 100}%`;
        track.append(scoreFill);
        const metrics = node("div", "iteration-metrics");
        [
            ["Hit@1", item.hitRateAt1],
            ["引用", item.citationCoverage],
            ["拒答", item.refusalAccuracy],
            ["失败", item.failureCount]
        ].forEach(([label, value]) => {
            const metric = node("span");
            metric.append(
                node("small", "", label),
                node("strong", "", label === "失败" ? String(value) : formatPercent(value, 0))
            );
            metrics.append(metric);
        });
        step.append(head, track, metrics);
        elements.overviewVersionLadder.append(step);
    });
    const note = node(
        "p",
        "glance-note",
        `${result.totalCases} 条问题 × ${result.summaries.length} 个版本，共 ${result.caseResults.length} 条结果；宽条代表 Hit@3。`
    );
    elements.evaluationGlance.querySelector(".glance-note")?.remove();
    elements.evaluationGlance.append(note);
}

function renderMetricMatrix(summaries) {
    elements.metricMatrix.replaceChildren();
    const groups = [
        ["检索命中 Hit@3", "hitRateAt3"],
        ["首条命中 Hit@1", "hitRateAt1"],
        ["引用覆盖率", "citationCoverage"],
        ["拒答准确率", "refusalAccuracy"]
    ];
    groups.forEach(([label, key]) => {
        const group = node("section", "metric-group");
        const head = node("div", "metric-group-head");
        head.append(node("strong", "", label), node("span", "", "V0 → V3"));
        const bars = node("div", "metric-bars");
        summaries.forEach(summary => {
            const version = findVersion(summary.version);
            const row = node("div", "metric-bar");
            const track = node("div", "progress-track");
            const fill = node("div", "metric-bar-fill");
            fill.style.width = `${clamp(summary[key], 0, 1) * 100}%`;
            track.append(fill);
            row.append(node("span", "", version?.code || summary.version), track, node("strong", "", formatPercent(summary[key], 0)));
            bars.append(row);
        });
        group.append(head, bars);
        elements.metricMatrix.append(group);
    });
}

function renderFailureSummary(cases) {
    const selectedVersion = elements.failureVersionFilter.value;
    const failures = cases.filter(item => {
        const versionMatch = !selectedVersion || item.version === selectedVersion;
        const failed = !item.retrievalHit || !item.citationPresent || !item.refusalCorrect;
        return versionMatch && failed;
    });
    elements.failureSummary.replaceChildren();
    if (!failures.length) {
        elements.failureSummary.append(emptyCompact("没有失败样本", "当前筛选范围内检索、引用与拒答均符合预期。"));
        return;
    }
    failures.slice(0, 7).forEach(item => {
        const version = findVersion(item.version);
        const row = node("div", "failure-item");
        const copy = node("div");
        copy.append(
            node("strong", "", item.question),
            node("p", "", `${item.caseId} · ${version?.code || item.version} · ${item.category} · ${failureReason(item)}`)
        );
        row.append(copy, node("span", "failure-rank", item.hitRank > 0 ? `RANK ${item.hitRank}` : "NO HIT"));
        elements.failureSummary.append(row);
    });
}

function renderCaseRows(cases) {
    const selectedVersion = elements.failureVersionFilter.value;
    const filtered = selectedVersion ? cases.filter(item => item.version === selectedVersion) : cases;
    elements.caseResultCount.textContent = `${filtered.length} 条`;
    elements.evaluationCaseRows.replaceChildren();
    filtered.forEach(item => {
        const row = node("tr");
        const version = findVersion(item.version);
        [
            item.caseId,
            item.category,
            version?.code || item.version
        ].forEach(value => row.append(node("td", "", value)));
        appendBooleanCell(row, item.retrievalHit);
        row.append(node("td", "", item.hitRank > 0 ? String(item.hitRank) : "--"));
        appendBooleanCell(row, item.citationPresent);
        appendBooleanCell(row, item.refusalCorrect);
        row.append(node("td", "", formatPercent(item.confidence, 0)));
        row.append(node("td", "", `${item.latencyMs} ms`));
        elements.evaluationCaseRows.append(row);
    });
}

async function importDocument(event) {
    event.preventDefault();
    if (!elements.importForm.reportValidity()) {
        return;
    }
    const form = new FormData(elements.importForm);
    const payload = {
        title: value(form, "title"),
        sourceType: value(form, "sourceType"),
        publisher: value(form, "publisher"),
        publicationYear: Number(value(form, "publicationYear")),
        authority: "运行时演示导入，需人工核验",
        evidenceLevel: value(form, "evidenceLevel"),
        disease: value(form, "disease"),
        syndromes: splitList(value(form, "syndromes")),
        formulas: splitList(value(form, "formulas")),
        herbs: splitList(value(form, "herbs")),
        section: value(form, "section"),
        version: value(form, "version"),
        licenseNote: value(form, "licenseNote"),
        keywords: splitList(value(form, "keywords")),
        content: value(form, "content")
    };
    setBusy(true);
    elements.importHint.textContent = "正在登记、切分并写入内存索引";
    try {
        const result = await request("/api/v1/knowledge/import", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        elements.importHint.textContent = `已登记 ${result.document.id}，生成 ${result.chunkCount} 个片段。`;
        elements.importForm.reset();
        await refreshKnowledgeData();
        showToast(`文档已登记并生成 ${result.chunkCount} 个知识片段`);
    } catch (error) {
        elements.importHint.textContent = `导入失败：${error.message}`;
        showToast(error.message, true);
    } finally {
        setBusy(false);
    }
}

function fillImportSample() {
    const sample = {
        title: "胸痹证候与益气活血治法演示摘要",
        publisher: "毕业设计演示数据组",
        sourceType: "临床研究",
        publicationYear: "2026",
        evidenceLevel: "B",
        disease: "冠心病",
        section: "辨证论治",
        version: "v1.0",
        syndromes: "气虚血瘀, 心脉痹阻",
        formulas: "补阳还五汤加减",
        herbs: "黄芪, 丹参, 川芎",
        keywords: "胸痹, 益气活血, 临床摘要",
        licenseNote: "仅用于原型导入演示，正式使用前需核验原文与授权",
        content:
            "本演示摘要用于验证知识库运行时入库流程。气虚血瘀证可见胸痛胸闷、气短乏力和动则加重，治法以益气活血、通脉止痛为主。具体方药、剂量及合并用药必须结合患者情况，由专业医师依据最新指南和药品说明书判断。中医药干预不能替代急性胸痛的急诊评估，也不能自行停用抗血小板或抗凝等基础治疗。"
    };
    Object.entries(sample).forEach(([name, value]) => {
        const field = elements.importForm.elements.namedItem(name);
        if (field) {
            field.value = value;
        }
    });
    elements.importHint.textContent = "演示样本已载入，可直接登记并观察自动切分结果。";
    showToast("已载入可公开演示的导入样本");
}

function renderRegistry() {
    elements.registryList.replaceChildren();
    elements.registrySummary.textContent = `${state.documents.length} 份来源，按登记时间排序`;
    if (!state.documents.length) {
        elements.registryList.append(emptyCompact("尚无登记记录", "导入文档后会在这里显示来源台账。"));
        return;
    }
    [...state.documents].reverse().forEach(document => {
        const item = node("article", "registry-item");
        const main = node("div", "registry-main");
        main.append(
            node("strong", "", document.title),
            node("p", "", `${document.publisher} · ${document.publicationYear} · ${document.sourceType}`)
        );
        const badges = node("div", "registry-badges");
        badges.append(
            node("span", "active", document.status),
            node("span", "", `${document.chunkCount} 片段`),
            node("span", "", `${document.evidenceLevel} 级`)
        );
        item.append(main, badges);
        elements.registryList.append(item);
    });
}

function setBusy(busy) {
    state.busy = busy;
    [
        elements.demoButton,
        elements.overviewDemoButton,
        elements.askButton,
        elements.compareButton,
        elements.globalEvaluateButton,
        elements.evaluationRunButton,
        elements.importButton
    ].forEach(button => {
        if (button) {
            button.disabled = busy;
        }
    });
}

async function runDemoSequence() {
    if (state.demoRunning) {
        return;
    }
    if (state.busy) {
        showToast("当前实验仍在运行，请稍候", true);
        return;
    }

    state.demoRunning = true;
    state.currentResult = null;
    state.compareResult = null;
    elements.copyAnswerButton.classList.add("hidden");
    elements.compareSection.classList.add("hidden");
    setView("workbench");

    if (state.versions.some(version => version.version === "V3_GUARDED")) {
        state.activeVersion = "V3_GUARDED";
        renderVersionRail();
    }
    elements.questionInput.value = demoQuestion;
    updateCharacterCount();

    try {
        showToast("演示 1/4：运行 V3 证据约束检索");
        await askQuestion();
        if (!state.currentResult) {
            throw new Error("演示检索未生成结果");
        }
        await wait(650);

        showToast("演示 2/4：对比 V0 到 V3 的检索与回答");
        await compareVersions();
        await wait(650);

        showToast("演示 3/4：进入量化评估实验");
        setView("evaluation");
        await runEvaluation();
        showToast("演示完成：知识底座 → 检索 → 引用 → 量化评估");
    } catch (error) {
        showToast(`演示中断：${error.message}`, true);
    } finally {
        state.demoRunning = false;
        elements.demoButton.disabled = false;
        elements.overviewDemoButton.disabled = false;
    }
}

function bestEvaluationVersion(summaries) {
    return summaries
        .map(summary => ({
            version: summary.version,
            score: summary.hitRateAt3 * 0.35
                + summary.citationCoverage * 0.3
                + summary.refusalAccuracy * 0.25
                + summary.meanReciprocalRank * 0.1
        }))
        .sort((left, right) => right.score - left.score)[0]?.version;
}

function evaluationMetric(label, value) {
    const metric = node("div", "evaluation-metric");
    const row = node("div", "evaluation-metric-row");
    row.append(node("span", "", label), node("strong", "", formatPercent(value, 0)));
    const track = node("div", "progress-track");
    const fill = node("div", "progress-fill");
    fill.style.width = `${clamp(value, 0, 1) * 100}%`;
    track.append(fill);
    metric.append(row, track);
    return metric;
}

function appendBooleanCell(row, passed) {
    row.append(node("td", passed ? "pass" : "fail", passed ? "通过" : "未通过"));
}

function failureReason(item) {
    if (!item.retrievalHit) return "检索未命中";
    if (!item.citationPresent) return "缺少引用";
    if (!item.refusalCorrect) return "拒答判断错误";
    return "未达到预期";
}

function evidenceTag(level) {
    const normalized = String(level || "").toUpperCase();
    const className = normalized === "B" ? "level-b" : normalized === "C" ? "level-c" : "";
    return node("span", `evidence-tag ${className}`.trim(), `${level || "--"} 级`);
}

function tagGroup(label, values) {
    const wrapper = node("div");
    wrapper.append(node("span", "", label));
    const tags = node("div", "tag-row");
    if (values?.length) {
        values.forEach(value => tags.append(node("span", "tag", value)));
    } else {
        tags.append(node("span", "tag", "未标注"));
    }
    wrapper.append(tags);
    return wrapper;
}

function emptyCompact(title, description) {
    const empty = node("div", "empty-compact");
    empty.append(node("strong", "", title), node("span", "", description));
    return empty;
}

function loadingBlock(message) {
    const wrapper = node("div", "loading-state");
    wrapper.append(node("span", "loading-ring"), node("span", "", message));
    return wrapper;
}

function node(tagName, className = "", text = null) {
    const element = document.createElement(tagName);
    if (className) {
        element.className = className;
    }
    if (text !== null && text !== undefined) {
        element.textContent = String(text);
    }
    return element;
}

function findVersion(versionKey) {
    return state.versions.find(version => version.version === versionKey);
}

function getActiveVersion() {
    return findVersion(state.activeVersion);
}

function unique(values) {
    return [...new Set(values.filter(value => value !== null && value !== undefined && String(value).trim()))]
        .map(value => String(value))
        .sort((left, right) => left.localeCompare(right, "zh-CN"));
}

function formatInteger(value) {
    return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

function formatDecimal(value, digits = 2) {
    return Number(value || 0).toFixed(digits);
}

function formatPercent(value, digits = 0) {
    return `${(clamp(Number(value || 0), 0, 1) * 100).toFixed(digits)}%`;
}

function signedPercent(value, digits = 0) {
    const number = Number(value || 0);
    return `${number >= 0 ? "+" : ""}${(number * 100).toFixed(digits)}%`;
}

function formatDateTime(value) {
    if (!value) {
        return "--";
    }
    return new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    }).format(new Date(value));
}

function formatTime(value) {
    if (!value) {
        return "未运行";
    }
    return new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    }).format(new Date(value));
}

function formatPipelineStatus(status) {
    return {
        READY: "完成",
        PARTIAL: "部分完成",
        WAITING: "等待"
    }[status] || status;
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, Number(value || 0)));
}

function wait(milliseconds) {
    return new Promise(resolve => window.setTimeout(resolve, milliseconds));
}

function value(formData, key) {
    return String(formData.get(key) || "").trim();
}

function splitList(value) {
    return value
        .split(/[,，、;\n]+/)
        .map(item => item.trim())
        .filter(Boolean);
}

function showToast(message, isError = false) {
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", isError);
    elements.toast.classList.add("visible");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => {
        elements.toast.classList.remove("visible");
    }, 3400);
}

async function request(url, options = {}) {
    const response = await fetch(url, {
        headers: {
            "Accept": "application/json",
            "Content-Type": "application/json"
        },
        ...options
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload || payload.code !== "OK") {
        throw new Error(payload?.message || `请求失败（HTTP ${response.status}）`);
    }
    return payload.data;
}
