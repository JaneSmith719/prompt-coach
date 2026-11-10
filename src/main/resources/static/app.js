/* PromptCoach 前端：原生 JS，无构建步骤、无第三方依赖。 */

const RING_CIRCUMFERENCE = 326.7;
const GRADE_COLORS = {S: '#34d8c2', A: '#4ade80', B: '#f0b45c', C: '#fb923c', D: '#f87171'};
/* 分享链接把需求编码进 URL，太长会被聊天工具截断，这里设个上限 */
const SHARE_MAX_CHARS = 600;

const state = {
    meta: null,
    templates: [],
    variants: [],
    activeTab: 0,
    busy: false
};

const $ = (selector) => document.querySelector(selector);

document.addEventListener('DOMContentLoaded', init);

async function init() {
    bindEvents();
    await Promise.all([loadMeta(), loadTemplates(), loadHistory()]);

    const shared = readSharedText();
    if (shared) {
        $('#input').value = shared;
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
            $('#input').value = button.dataset.example;
            $('#input').focus();
        });
    });
    $('#copy').addEventListener('click', copyPrompt);
    $('#share').addEventListener('click', shareLink);
    $('#clear-history').addEventListener('click', clearHistory);
}

async function loadMeta() {
    try {
        const meta = await (await fetch('/api/meta')).json();
        state.meta = meta;
        const badge = $('#mode-badge');
        if (meta.llmAvailable) {
            badge.textContent = '已连接 ' + meta.model;
            badge.className = 'badge badge-live';
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
                $('#input').value = template.example;
                $('#input').focus();
                toast('已填入「' + template.name + '」的示例，直接点诊断即可');
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
        const response = await fetch('/api/coach', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({text: text, enhance: $('#enhance').checked})
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

    const hint = $('#placeholder-hint');
    hint.textContent = report.placeholders && report.placeholders.length
        ? `还有 ${report.placeholders.length} 处待补充：${report.placeholders.join('、')}`
        : '五要素已齐全，可直接使用';
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
    state.activeTab = 0;
    const tabs = $('#tabs');
    tabs.innerHTML = state.variants.map((variant, index) => `
        <button type="button" class="tab${index === 0 ? ' active' : ''}" data-tab="${index}"
                title="${escapeHtml(variant.description)}">${escapeHtml(variant.label)}</button>`).join('');
    tabs.querySelectorAll('.tab').forEach((button) => {
        button.addEventListener('click', () => {
            state.activeTab = Number(button.dataset.tab);
            tabs.querySelectorAll('.tab').forEach((item) => item.classList.remove('active'));
            button.classList.add('active');
            $('#prompt-text').textContent = state.variants[state.activeTab].prompt;
        });
    });
    $('#prompt-text').textContent = state.variants.length ? state.variants[0].prompt : '';
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

/** 复制分享链接：把当前需求编码进 URL，别人打开就自动诊断 */
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
    const link = `${window.location.origin}${window.location.pathname}?q=${encodeShare(text)}`;
    await copyText(link, '分享链接已复制，打开即自动诊断');
}

/** 读取 ?q= 参数，解不出内容时给个提示而不是白屏 */
function readSharedText() {
    const value = new URLSearchParams(window.location.search).get('q');
    if (!value) {
        return null;
    }
    try {
        const text = decodeShare(value);
        return text && text.trim() ? text : null;
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

function setBusy(busy) {
    state.busy = busy;
    const button = $('#run');
    button.disabled = busy;
    button.firstChild.textContent = busy ? '诊断中…' : '诊断并优化';
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