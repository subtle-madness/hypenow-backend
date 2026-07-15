// 페이지 이동 후 돌아와도 폼 상태 유지 (탭 단위 — sessionStorage)
document.addEventListener('DOMContentLoaded', function () {
    // 1) data-persist 필드: 값 저장·복원 (잡 실행 페이지의 카테고리 선택, 체크박스 등)
    document.querySelectorAll('[data-persist]').forEach(function (el) {
        var key = 'persist:' + location.pathname + ':' + (el.name || el.id);
        var saved = sessionStorage.getItem(key);
        if (saved !== null) {
            if (el.type === 'checkbox') {
                el.checked = saved === 'true';
            } else if (el.tagName === 'SELECT') {
                // 저장된 값의 옵션이 사라졌으면(카테고리 삭제 등) 복원하지 않는다
                var exists = Array.prototype.some.call(el.options, function (o) { return o.value === saved; });
                if (exists) el.value = saved;
            } else {
                el.value = saved;
            }
        }
        el.addEventListener('change', function () {
            sessionStorage.setItem(key, el.type === 'checkbox' ? String(el.checked) : el.value);
        });
    });

    // 2) 수집 데이터: 필터 상태는 URL이 원본 — 쿼리 없이 진입하면 마지막 쿼리로 복귀
    if (document.body.dataset.restoreQuery === 'true') {
        var qKey = 'persist:query:' + location.pathname;
        if (!location.search && sessionStorage.getItem(qKey)) {
            location.replace(location.pathname + sessionStorage.getItem(qKey));
        } else {
            sessionStorage.setItem(qKey, location.search);
        }
    }
});
