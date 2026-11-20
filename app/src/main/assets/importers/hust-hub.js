// Vue parent traversal adapted from WadwaitaUp (MIT, assets/licenses/WadwaitaUp-LICENSE.txt).
(function (token, verify) {
    'use strict';
    const route = '/basicInformation/scheduleInformation/index';
    if (location.origin !== 'https://hubs.hust.edu.cn' || location.pathname !== route ||
        location.hash || window !== window.top) return JSON.stringify('wrong_route');
    const guardKey = '__stacklife_hub_guard_' + token;
    if (verify) {
        const check = window[guardKey];
        delete window[guardKey];
        return typeof check === 'function' && check();
    }
    function findRows(el) {
        let vm = el && el.__vue__;
        const seen = new Set();
        while (vm && !seen.has(vm)) {
            seen.add(vm);
            for (const rows of [vm.weekTableData, vm.$data && vm.$data.weekTableData]) {
                if (Array.isArray(rows) && rows.length) return rows;
            }
            vm = vm.$parent;
        }
        return null;
    }
    function table() {
        return findRows(document.querySelector('.el-table')) || findRows(document.querySelector('.el-tabs')) || findRows(document.body);
    }
    const loading = () => Array.from(document.querySelectorAll('.el-loading-mask')).some(el => el.getClientRects().length !== 0);
    if (loading()) return JSON.stringify('loading');
    const rows = table();
    if (!rows) return JSON.stringify('no_table');
    const days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
    function scalar(v) { return v == null || ['string', 'number', 'boolean'].includes(typeof v) ? v : false; }
    function project() {
        if (rows.length > 2048) throw new RangeError();
        let count = 0;
        const result = rows.map(row => {
            if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
            const week = {ZC: scalar(row.ZC), KS: scalar(row.KS)};
            for (const day of days) {
                if (!(day in row)) continue;
                const items = row[day];
                if (items == null) { week[day] = null; continue; }
                if (!Array.isArray(items)) { week[day] = false; continue; }
                count += items.length;
                if (count > 16000) throw new RangeError();
                week[day] = items.map(item => !item || typeof item !== 'object' || Array.isArray(item) ? null :
                    {KCMC: scalar(item.KCMC), JC: scalar(item.JC), JSMC: scalar(item.JSMC)});
            }
            return week;
        });
        const text = JSON.stringify(result);
        if (text.length > 1048576) throw new RangeError();
        return text;
    }
    try {
        const href = location.href;
        const snapshot = project();
        let changed = false;
        const invalidate = () => { changed = true; };
        const events = ['input', 'change', 'click', 'keydown', 'pagehide', 'popstate', 'hashchange'];
        for (const e of events) window.addEventListener(e, invalidate, true);
        const observer = new MutationObserver(invalidate);
        observer.observe(document.documentElement, {childList: true, subtree: true, characterData: true, attributes: true});
        const clean = () => {
            observer.disconnect();
            for (const e of events) window.removeEventListener(e, invalidate, true);
            delete window[guardKey];
        };
        const timer = setTimeout(clean, 15000);
        window[guardKey] = function () {
            changed = changed || observer.takeRecords().length > 0;
            clearTimeout(timer);
            clean();
            try { return !changed && location.href === href && !loading() && table() === rows && project() === snapshot; }
            catch (_) { return false; }
        };
        return snapshot;
    } catch (e) { return JSON.stringify(e instanceof RangeError ? 'too_large' : 'no_table'); }
})
