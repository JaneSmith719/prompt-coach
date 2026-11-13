/* PromptCoach 前端：原生 JS，无构建步骤、无第三方依赖。 */

const RING_CIRCUMFERENCE = 326.7;
const GRADE_COLORS = {S: '#34d8c2', A: '#4ade80', B: '#f0b45c', C: '#fb923c', D: '#f87171'};
/* 分享链接把需求编码进 URL，太长会被聊天工具截断，这里设个上限 */
const SHARE_MAX_CHARS = 600;
/* 收藏只存在浏览器本地，不经过服务端 */
const FAVORITES_KEY = 'promptcoach.favorites.v1';
const FAVORITES_MAX = 50;

const state = {
    meta: null,
    templates: [],
    variants: [],
    activeTab: 0,
    busy: false,
    templateId: null,
    templateName: null,
    currentReport: null,
    favorites: [],
    favoriteWarned: false
};

const $ = (selector) => document.querySelector(selector);

document.addEventListener('DOMContentLoaded', init);

async function init() {
    bindEvents();
    await Promise.all([loadMeta(), loadTemplates(), loadHistory()]);
    loadFavorites();

    const shared = readShareState();
    if (shared) {
        if (shared.templateId) {
            applyTemplateState(shared.templateId);
        }
        $('#input').value = shared.text;
        run();
    }
}

function bindEvents() {
    $('#run').addEventListener('click', run);
    $('#input').addEventListener('keydown', (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
            event.preventDefault();
            run();
        }
    });
    document.querySelectorAll('[data-example]').forEach((button) => {
        button.addEventListener('click', () => {
            clearTemplateState();
            $('#input').value = button.dataset.example;
            $('#input').focus();
        });
    });
    $('#copy').addEventListener('click', copyPrompt);
    $('#share').addEventListener('click', shareLink);
    $('#clear-history').addEventListener('click', clearHistory);
    $('#favorite').addEventListener('click', saveFavorite);
    $('#export').addEventListener('click', exportCurrent);
    $('#export-favorites').addEventListener('click', exportFavorites);
    $('#clear-favorites').addEventListener('click', clearFavorites);
    $('#template-active').addEventListener('click', (event) => {
        if (event.target.closest('#clear-template')) {
            clearTemplateState();
        }
    });
    $('#favorites').addEventListener('click', (event) => {
        const deleteButton = event.target.closest('[data-delete-favorite]');
        if (deleteButton) {
            deleteFavorite(deleteButton.dataset.deleteFavorite);
            return;
        }
        const openButton = event.target.closest('[data-open-favorite]');
        if (openButton) {
            restoreFavorite(openButton.dataset.openFavorite);
        }
    });
}

async function loadMeta() {
    try {
        const meta = await (await fetch('/api/meta')).json();
        state.meta = meta;
        const badge = $('#mode-badge');
        if (meta.llmAvailable) {
            badge.textContent = '已连接 ' + meta.model;
            badge.className = 'badge badge-live';
            if (meta.enhanceEnabled === false) {
                $('#enhance-hint').textContent = '增强限流已关闭';
            } else if (meta.enhancePerHour > 0) {
                $('#enhance-hint').textContent = `每小时最多 ${meta.enhancePerHour} 次增强`;
            } else {
                $('#enhance-hint').textContent = '增强次数不限';
            }
        } else {
            badge.textContent = '本地规则引擎';
            badge.className = 'badge';
            $('#enhance').disabled = true;
            $('#enhance').checked = false;
            $('#enhance-hint').textContent = '未配置 AI_API_KEY，本地模式照常可用';
        }
    } catch (error) {
        $('#mode-badge').textContent = '离线';
    }
}

async function loadTemplates() {
    try {
        const templates = await (await fetch('/api/templates')).json();
        state.templates = templates;
        $('#template-count').textContent = templates.length + ' 个';
        $('#templates').innerHTML = templates.map((template) => `
            <button type="button" class="template-item" data-template="${escapeHtml(template.id)}"
                    title="${escapeHtml(template.intent)}">
                <span>${escapeHtml(template.name)}</span>
                <span class="template-cat">${escapeHtml(template.category)}</span>
            </button>`).join('');
        document.querySelectorAll('[data-template]').forEach((button) => {
            button.addEventListener('click', () => {
                const template = state.templates.find((item) => item.id === button.dataset.template);
                if (!template) {
                    return;
                }
                applyTemplateState(template.id);
                $('#input').value = template.example;
                $('#input').focus();
                toast('已套用「' + template.name + '」模板，直接点诊断即可');
            });
        });
    } catch (error) {
        $('#templates').innerHTML = '<p class="empty">模板加载失败</p>';
    }
}

async function loadHistory() {
    try {
        const history = await (await fetch('/api/history?limit=12')).json();
        const list = $('#history');
        if (!history.length) {
            list.innerHTML = '<li class="empty">还没有记录</li>';
            return;
        }
        list.innerHTML = history.map((entry) => `
            <li>
                <button type="button" class="history-item" data-text="${escapeHtml(entry.text)}">
                    <span class="history-score">${entry.score}</span>
                    <span class="history-text">${escapeHtml(entry.text)}</span>
                </button>
            </li>`).join('');
        list.querySelectorAll('.history-item').forEach((button) => {
            button.addEventListener('click', () => {
                clearTemplateState();
                $('#input').value = button.dataset.text;
                $('#input').focus();
                window.scrollTo({top: 0, behavior: 'smooth'});
            });
        });
    } catch (error) {
        /* 历史记录不是关键路径，失败就静默 */
    }
}

async function run() {
    if (state.busy) {
        return;
    }
    const text = $('#input').value.trim();
    if (!text) {
        showError('先写一句你的需求，比如「帮我写个周报」');
        $('#input').focus();
        return;
    }

    setBusy(true);
    hideError();
    try {
        const payload = {text: text, enhance: $('#enhance').checked};
        if (state.templateId) {
            payload.templateId = state.templateId;
        }
        const response = await fetch('/api/coach', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await response.json();
        if (!response.ok) {
            showError(data.message || '请求失败，请重试');
            return;
        }
        render(data);
        if (window.innerWidth < 980) {
            $('#result').scrollIntoView({behavior: 'smooth', block: 'start'});
        }
        await loadHistory();
    } catch (error) {
        showError('网络异常：' + error.message);
    } finally {
        setBusy(false);
    }
}

function render(report) {
    const diagnosis = report.diagnosis;
    const color = GRADE_COLORS[diagnosis.grade] || '#8b7bff';

    state.currentReport = report;
    $('#result').classList.remove('hidden');
    $('#score').textContent = String(diagnosis.score);
    $('#grade').textContent = diagnosis.grade;
    $('#grade').style.color = color;
    $('#summary').textContent = diagnosis.summary;

    const ring = $('#ring-fg');
    ring.style.strokeDashoffset = String(RING_CIRCUMFERENCE);
    requestAnimationFrame(() => {
        ring.style.stroke = color;
        ring.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - diagnosis.score / 100));
    });

    $('#coverage').textContent = `覆盖 ${report.coverage}/5 个维度`;
    $('#elapsed').textContent = `耗时 ${report.elapsedMs} ms`;
    $('#mode-chip').textContent = report.mode === 'llm'
        ? `大模型增强 · ${report.model}`
        : '本地规则引擎';

    const templateChip = $('#template-chip');
    if (report.templateName) {
        templateChip.textContent = '模板：' + report.templateName;
        templateChip.classList.remove('hidden');
    } else {
        templateChip.classList.add('hidden');
        templateChip.textContent = '';
    }

    const notice = $('#notice');
    if (report.notice) {
        notice.textContent = report.notice;
        notice.classList.remove('hidden');
    } else {
        notice.classList.add('hidden');
    }

    renderDimensions(diagnosis.dimensions);
    renderList('#gaps', diagnosis.gaps, '没有发现明显缺口');
    renderList('#questions', diagnosis.questions, '需求已经很完整，可以直接发给 AI 了');
    renderVariants(report.variants);
    renderAdvice(report.advice);
    renderSteps(report.nextSteps);
    renderTemplateTips(report.templateTips);

    if (report.templateId) {
        state.templateId = report.templateId;
        state.templateName = report.templateName;
    } else {
        state.templateId = null;
        state.templateName = null;
    }
    renderTemplateActive();

    const hint = $('#placeholder-hint');
    hint.textContent = report.placeholders && report.placeholders.length
        ? `整句版已用通用要求补齐；还有 ${report.placeholders.length} 处可补充：${report.placeholders.join('、')}`
        : '五要素已齐全，可直接使用';
    updateFavoriteButton();
}

function renderTemplateTips(tips) {
    const block = $('#template-tips');
    if (!tips || !tips.length) {
        block.classList.add('hidden');
        $('#template-tips-list').innerHTML = '';
        return;
    }
    renderList('#template-tips-list', tips, '');
    block.classList.remove('hidden');
}

function renderTemplateActive() {
    const chip = $('#template-active');
    if (!state.templateId) {
        chip.classList.add('hidden');
        chip.innerHTML = '';
        return;
    }
    chip.innerHTML = `<span>已套用：${escapeHtml(state.templateName)}</span>
        <button type="button" id="clear-template" aria-label="取消当前模板">×</button>`;
    chip.classList.remove('hidden');
}

function applyTemplateState(id) {
    const template = state.templates.find((item) => item.id === id);
    if (!template) {
        return false;
    }
    state.templateId = template.id;
    state.templateName = template.name;
    renderTemplateActive();
    return true;
}

function clearTemplateState() {
    state.templateId = null;
    state.templateName = null;
    renderTemplateActive();
}

function renderDimensions(dimensions) {
    const container = $('#dims');
    container.innerHTML = dimensions.map((dimension) => {
        const ratio = dimension.max ? dimension.score / dimension.max : 0;
        const level = ratio >= 0.8 ? 'good' : ratio >= 0.5 ? 'mid' : 'low';
        return `
            <div class="dim" data-ratio="${ratio}">
                <div class="dim-head">
                    <span class="dim-name">${escapeHtml(dimension.name)}</span>
                    <span class="dim-score">${dimension.score} / ${dimension.max}</span>
                </div>
                <div class="dim-bar"><i class="${level}"></i></div>
                <p class="dim-evidence">${escapeHtml(dimension.evidence)}</p>
            </div>`;
    }).join('');

    container.querySelectorAll('.dim').forEach((element) => {
        const bar = element.querySelector('.dim-bar i');
        requestAnimationFrame(() => {
            bar.style.width = Math.round(Number(element.dataset.ratio) * 100) + '%';
        });
    });
}

function renderList(selector, items, emptyText) {
    const element = $(selector);
    if (!items || !items.length) {
        element.innerHTML = `<li class="empty">${escapeHtml(emptyText || '没有更多内容')}</li>`;
        return;
    }
    element.innerHTML = items.map((item) => `<li>${escapeHtml(item)}</li>`).join('');
}

function renderVariants(variants) {
    state.variants = variants || [];
    const tabs = $('#tabs');
    tabs.innerHTML = state.variants.map((variant, index) => `
        <button type="button" class="tab${index === 0 ? ' active' : ''}" data-tab="${index}"
                title="${escapeHtml(variant.description)}">${escapeHtml(variant.label)}</button>`).join('');
    tabs.querySelectorAll('.tab').forEach((button) => {
        button.addEventListener('click', () => selectVariant(Number(button.dataset.tab)));
    });
    selectVariant(0);
}

function selectVariant(index) {
    if (!state.variants.length) {
        state.activeTab = 0;
        $('#prompt-text').textContent = '';
        return;
    }
    const safe = Math.min(Math.max(Number(index) || 0, 0), state.variants.length - 1);
    state.activeTab = safe;
    document.querySelectorAll('#tabs .tab').forEach((item, position) => {
        item.classList.toggle('active', position === safe);
    });
    $('#prompt-text').textContent = state.variants[safe].prompt;
}

function renderAdvice(advice) {
    const element = $('#advice');
    element.innerHTML = (advice || []).map((item) => `
        <li>
            <span class="advice-title">
                ${escapeHtml(item.title)}
                <span class="advice-level${item.level === '高' ? ' high' : ''}">${escapeHtml(item.level)}</span>
            </span>
            <span class="advice-detail">${escapeHtml(item.detail)}</span>
        </li>`).join('');
}

function renderSteps(steps) {
    $('#next').innerHTML = (steps || []).map((step) => `<li>${escapeHtml(step)}</li>`).join('');
}

async function copyPrompt() {
    const text = $('#prompt-text').textContent;
    if (!text) {
        return;
    }
    await copyText(text, '提示词已复制');
}

/** 复制分享链接：把当前需求（和模板）编码进 URL，别人打开就自动诊断 */
async function shareLink() {
    const text = $('#input').value.trim();
    if (!text) {
        toast('先写一句需求，再复制分享链接');
        return;
    }
    if (text.length > SHARE_MAX_CHARS) {
        toast(`内容超过 ${SHARE_MAX_CHARS} 字，链接可能被聊天工具截断，建议精简后再分享`);
        return;
    }
    const params = new URLSearchParams();
    params.set('q', encodeShare(text));
    if (state.templateId) {
        params.set('t', state.templateId);
    }
    const link = `${window.location.origin}${window.location.pathname}?${params.toString()}`;
    await copyText(link, '分享链接已复制，打开即自动诊断');
}

/** 读取 ?q=&t=，解不出内容时给个提示而不是白屏 */
function readShareState() {
    const params = new URLSearchParams(window.location.search);
    const value = params.get('q');
    if (!value) {
        return null;
    }
    try {
        const text = decodeShare(value);
        if (!text || !text.trim()) {
            return null;
        }
        return {text: text, templateId: params.get('t')};
    } catch (error) {
        toast('分享链接已损坏，请让分享者重新复制');
        return null;
    }
}

/** UTF-8 文本 → Base64URL（去掉 +/= 三种会被 URL 吃掉或被转义的字符） */
function encodeShare(text) {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    bytes.forEach((byte) => {
        binary += String.fromCharCode(byte);
    });
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function decodeShare(value) {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    return new TextDecoder().decode(bytes);
}

async function copyText(text, message) {
    try {
        await navigator.clipboard.writeText(text);
        toast(message || '已复制到剪贴板');
    } catch (error) {
        const helper = document.createElement('textarea');
        helper.value = text;
        document.body.appendChild(helper);
        helper.select();
        document.execCommand('copy');
        document.body.removeChild(helper);
        toast(message || '已复制到剪贴板');
    }
}

async function clearHistory() {
    try {
        await fetch('/api/history', {method: 'DELETE'});
        await loadHistory();
        toast('历史已清空');
    } catch (error) {
        toast('清空失败');
    }
}

/* ---------------- 收藏夹：只存 localStorage，不上传服务器 ---------------- */

function loadFavorites() {
    try {
        const raw = localStorage.getItem(FAVORITES_KEY);
        if (!raw) {
            state.favorites = [];
            renderFavorites();
            return;
        }
        const parsed = JSON.parse(raw);
        const items = parsed && Array.isArray(parsed.items) ? parsed.items : [];
        state.favorites = items.filter(isValidFavorite).slice(0, FAVORITES_MAX);
        renderFavorites();
    } catch (error) {
        state.favorites = [];
        renderFavorites();
        warnFavoriteOnce('收藏数据损坏或浏览器存储不可用，已重置为空');
    }
}

function isValidFavorite(item) {
    const report = item && item.report;
    return Boolean(item)
        && typeof item.fingerprint === 'string'
        && report
        && typeof report.original === 'string'
        && Array.isArray(report.variants)
        && report.diagnosis
        && typeof report.diagnosis.score === 'number';
}

function renderFavorites() {
    const list = $('#favorites');
    if (!state.favorites.length) {
        list.innerHTML = '<li class="empty">还没有收藏</li>';
    } else {
        list.innerHTML = state.favorites.map((item) => {
            const report = item.report;
            const text = report.original || '';
            return `
                <li class="favorite-row">
                    <button type="button" class="favorite-open"
                            data-open-favorite="${escapeHtml(item.fingerprint)}"
                            title="${escapeHtml(text)}">
                        <span class="favorite-score">${escapeHtml(report.diagnosis.score)}</span>
                        <span class="favorite-text">${escapeHtml(text)}</span>
                    </button>
                    <button type="button" class="favorite-delete"
                            data-delete-favorite="${escapeHtml(item.fingerprint)}"
                            aria-label="删除这条收藏">×</button>
                </li>`;
        }).join('');
    }
    $('#favorite-count').textContent = state.favorites.length + ' 条';
}

function saveFavorite() {
    const report = state.currentReport;
    if (!report) {
        return;
    }
    const fingerprint = reportFingerprint(report);
    const existing = state.favorites.find((item) => item.fingerprint === fingerprint);
    const item = {
        fingerprint: fingerprint,
        savedAt: new Date().toISOString(),
        activeVariant: state.activeTab,
        report: report
    };
    let updated = false;
    if (existing) {
        Object.assign(existing, item);
        updated = true;
    } else {
        state.favorites.unshift(item);
        if (state.favorites.length > FAVORITES_MAX) {
            state.favorites.pop();
        }
    }

    const persisted = persistFavorites();
    renderFavorites();
    updateFavoriteButton();
    if (!persisted) {
        toast('已收藏到当前页面，但浏览器存储不可用，刷新后会丢失');
    } else {
        toast(updated ? '已更新收藏' : '已收藏，只保存在本浏览器');
    }
}

function persistFavorites() {
    try {
        localStorage.setItem(FAVORITES_KEY, JSON.stringify({version: 1, items: state.favorites}));
        return true;
    } catch (error) {
        /* 配额满：丢掉最旧一条再试一次；普通禁用存储则直接放弃 */
        if (state.favorites.length > 1 && isQuotaError(error)) {
            const removed = state.favorites.pop();
            try {
                localStorage.setItem(FAVORITES_KEY, JSON.stringify({version: 1, items: state.favorites}));
                return true;
            } catch (ignored) {
                state.favorites.push(removed);
            }
        }
        return false;
    }
}

function isQuotaError(error) {
    return Boolean(error) && (
        error.name === 'QuotaExceededError'
        || error.name === 'NS_ERROR_DOM_QUOTA_REACHED'
        || error.code === 22
        || error.code === 1014
    );
}

function restoreFavorite(fingerprint) {
    const item = state.favorites.find((favorite) => favorite.fingerprint === fingerprint);
    if (!item) {
        toast('这条收藏已经不存在');
        return;
    }
    state.templateId = item.report.templateId || null;
    state.templateName = item.report.templateName || null;
    renderTemplateActive();
    $('#input').value = item.report.original || '';
    render(item.report);
    selectVariant(item.activeVariant || 0);
    window.scrollTo({top: 0, behavior: 'smooth'});
    toast('已恢复收藏（不会重新请求服务端）');
}

function deleteFavorite(fingerprint) {
    state.favorites = state.favorites.filter((item) => item.fingerprint !== fingerprint);
    persistFavorites();
    renderFavorites();
    updateFavoriteButton();
    toast('已删除收藏');
}

function clearFavorites() {
    if (!state.favorites.length) {
        toast('收藏夹已经是空的');
        return;
    }
    if (!window.confirm('清空本浏览器保存的收藏？此操作不可恢复。')) {
        return;
    }
    state.favorites = [];
    try {
        localStorage.removeItem(FAVORITES_KEY);
    } catch (error) {
        /* 存储不可用时只需要清空内存状态 */
    }
    renderFavorites();
    updateFavoriteButton();
    toast('收藏夹已清空');
}

function updateFavoriteButton() {
    const button = $('#favorite');
    if (!state.currentReport) {
        button.disabled = true;
        button.textContent = '收藏';
        return;
    }
    button.disabled = false;
    button.textContent = isFavorite(state.currentReport) ? '已收藏' : '收藏';
}

function isFavorite(report) {
    const fingerprint = reportFingerprint(report);
    return state.favorites.some((item) => item.fingerprint === fingerprint);
}

function reportFingerprint(report) {
    return 'djb2:' + hashText((report.original || '') + '\u0000' + (report.structuredPrompt || ''));
}

function hashText(text) {
    let hash = 5381;
    for (let index = 0; index < text.length; index++) {
        hash = ((hash << 5) + hash + text.charCodeAt(index)) >>> 0;
    }
    return hash.toString(16);
}

function warnFavoriteOnce(message) {
    if (state.favoriteWarned) {
        return;
    }
    state.favoriteWarned = true;
    toast(message);
}

/* ---------------- Markdown 导出 ---------------- */

function exportCurrent() {
    if (!state.currentReport) {
        toast('先诊断一次再导出');
        return;
    }
    downloadMarkdown(toMarkdown(state.currentReport), `promptcoach-report-${fileStamp()}.md`);
}

function exportFavorites() {
    if (!state.favorites.length) {
        toast('收藏夹还是空的');
        return;
    }
    const header = [
        '# PromptCoach 收藏夹',
        '',
        `共 ${state.favorites.length} 条，导出时间 ${new Date().toLocaleString('zh-CN')}`,
        ''
    ].join('\n');
    const body = state.favorites.map((item) => toMarkdown(item.report)).join('\n\n---\n\n');
    downloadMarkdown(header + '\n' + body, `promptcoach-favorites-${fileStamp()}.md`);
}

function toMarkdown(report) {
    const diagnosis = report.diagnosis || {};
    const lines = [];
    lines.push('# PromptCoach 诊断报告', '');
    lines.push('## 原始需求', '', fenced(report.original || ''), '');
    lines.push('## 评分', '');
    lines.push(`- 得分：${diagnosis.score == null ? '-' : diagnosis.score} / ${diagnosis.maxScore == null ? 100 : diagnosis.maxScore}（${diagnosis.grade || '-'} 级）`);
    lines.push(`- 模式：${report.mode === 'llm' ? '大模型增强' + (report.model ? `（${report.model}）` : '') : '本地规则引擎'}`);
    if (report.createdAt) {
        lines.push(`- 时间：${new Date(report.createdAt).toLocaleString('zh-CN')}`);
    }
    if (report.templateName) {
        lines.push(`- 模板：${report.templateName}`);
    }
    if (report.notice) {
        lines.push(`- 提示：${report.notice}`);
    }

    lines.push('', '## 五维评分', '', '| 维度 | 得分 | 满分 | 依据 |', '| --- | --- | --- | --- |');
    (diagnosis.dimensions || []).forEach((dimension) => {
        lines.push(`| ${markdownCell(dimension.name)} | ${dimension.score} | ${dimension.max} | ${markdownCell(dimension.evidence)} |`);
    });

    lines.push('', '## 还缺什么', '');
    appendMarkdownList(lines, diagnosis.gaps, '没有发现明显缺口');

    if (diagnosis.questions && diagnosis.questions.length) {
        lines.push('', '## 需要想清楚的问题', '');
        appendMarkdownList(lines, diagnosis.questions, '');
    }

    const primary = (report.variants && report.variants[0])
        || {label: '结构化版', prompt: report.structuredPrompt || ''};
    lines.push('', `## 推荐提示词 · ${primary.label}`, '', fenced(primary.prompt || ''), '');

    const others = (report.variants || []).filter((variant) => variant.prompt !== primary.prompt);
    if (others.length) {
        lines.push('## 其他版本', '');
        others.forEach((variant) => {
            lines.push(`### ${variant.label}`, '', variant.description || '', '', fenced(variant.prompt || ''), '');
        });
    }

    lines.push('## 提效建议', '');
    (report.advice || []).forEach((advice) => {
        lines.push(`- **${advice.title}**（${advice.level}）：${advice.detail}`);
    });

    lines.push('', '## 下一步', '');
    (report.nextSteps || []).forEach((step) => lines.push(`- ${step}`));
    return lines.join('\n');
}

function appendMarkdownList(lines, items, emptyText) {
    if (!items || !items.length) {
        if (emptyText) {
            lines.push(`- ${emptyText}`);
        }
        return;
    }
    items.forEach((item) => lines.push(`- ${item}`));
}

/** 动态代码围栏：正文里出现连续反引号时，围栏永远比它长一截 */
function fenced(text) {
    const content = String(text == null ? '' : text);
    const runs = content.match(/`+/g) || [];
    const longest = runs.reduce((max, run) => Math.max(max, run.length), 0);
    const fence = '`'.repeat(Math.max(3, longest + 1));
    return `${fence}text\n${content}\n${fence}`;
}

function markdownCell(value) {
    return String(value == null ? '' : value).replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
}

function downloadMarkdown(markdown, filename) {
    const blob = new Blob([markdown], {type: 'text/markdown;charset=utf-8'});
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('Markdown 已开始下载');
}

function fileStamp() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, '0');
    return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
}

/* ---------------- 状态与通用工具 ---------------- */

function setBusy(busy) {
    state.busy = busy;
    const button = $('#run');
    button.disabled = busy;
    $('#run-label').textContent = busy ? '诊断中…' : '诊断并优化';
}

function showError(message) {
    const element = $('#error');
    element.textContent = message;
    element.classList.remove('hidden');
}

function hideError() {
    $('#error').classList.add('hidden');
}

let toastTimer = null;

function toast(message) {
    const element = $('#toast');
    element.textContent = message;
    element.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => element.classList.add('hidden'), 2000);
}

function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, (char) => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
    }[char]));
}