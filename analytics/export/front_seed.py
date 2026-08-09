#!/usr/bin/env python3
"""analysis DB -> celfit-front 실데이터셋 3종 변환 (프론트 실제 뷰 데모용).

사용법:  python3 analytics/export/front_seed.py [celfit-front 경로]
         (기본 경로 ~/Project/celfit-front · crawler-postgres-1 기동 필요)
         이후 프론트에서 `pnpm dev` — DATABASE_URL 없는 인메모리 모드가 이 파일들을 읽는다.

산출: <front>/src/db/seed/real/{dataset.json, deep-dives.json, account-reports.json} 덮어쓰기.
      원복은 프론트 저장소에서 git checkout.
대상: content_analyses가 있는 게시물(분석 완료)과 그 계정.
LLM 카피가 없는 곳은 결정적 폴백 문구/기본값을 쓰고, 그 사실을 stdout에 집계한다.

주의:
  - aggregatedAt은 프론트 기본 랭킹 주(DEFAULT_FILTER, 아래 WEEK)로 리매핑 —
    프론트 자체 파이프라인(real-data-pipeline)과 동일한 관용구.
  - 썸네일·아바타는 프론트 public/{thumbs,avatars}에 같은 short_code/handle 파일이
    있으면 재사용, 없으면 picsum placeholder (인스타 CDN URL은 서명 만료).
"""
import json
import os
import subprocess
import sys
from datetime import date, timedelta

FRONT = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Project/celfit-front")
OUT = os.path.join(FRONT, "src/db/seed/real")
TODAY = date.today()
# 프론트 기본 랭킹 집계 주 (celfit-front DEFAULT_FILTER와 1:1 — 프론트가 바뀌면 같이 수정)
WEEK = [date(2026, 6, 27) + timedelta(days=i) for i in range(7)]
CAT_LABEL = {"makeup": "메이크업", "skincare": "스킨케어", "suncare": "선케어",
             "cleansing": "클렌징", "haircare": "헤어케어", "hair": "헤어케어",
             "fragrance": "향수/디퓨저", "esthetic": "에스테틱", "etc": "기타"}
COMMENT_CATS = ["purchase", "friendTag", "positive", "question", "adAware", "etc"]

if not os.path.isdir(OUT):
    sys.exit(f"celfit-front 시드 디렉토리가 없습니다: {OUT} — 프론트 클론 경로를 인자로 지정하세요.")


def q(sql):
    r = subprocess.run(["docker", "exec", "crawler-postgres-1", "psql", "-U", "crawler",
                        "-d", "analysis", "-t", "-A", "-c", sql],
                       capture_output=True, text=True, check=True)
    out = r.stdout.strip()
    return json.loads(out) if out else []


def d(iso):  # timestamptz -> 'YYYY-MM-DD'
    return iso[:10] if iso else None


rows = q("""SELECT json_agg(row_to_json(t)) FROM (
  SELECT c.short_code, c.account_handle, c.caption, c.posted_at, c.content_type,
         c.views, c.likes, c.comments, c.hype_score, c.video_duration, c.original_url,
         a.display_name, a.followers, a.profile_image_url,
         ca.ai_content_summary, ca.contents_pattern, ca.ai_comment_insight,
         ca.recent_reels_avg_views, ca.rank_in_recent_reels, ca.recent_reels_count,
         ca.recent12_avg_engagement_rate, ca.recent12_avg_like_count, ca.recent12_avg_comment_count,
         ca.category_top_percentile, ca.category_avg_views, ca.category_sample_size,
         ca.detected_brands, ca.sponsored_signal_level, ca.sponsored_signal_reasons,
         ca.ad_disclosure, ca.detected_product_categories, ca.vlm_attributes,
         ca.main_category, ca.sub_categories, ca.ad_type, ca.detected_distributors,
         ca.comment_authenticity_grade, ca.comment_authenticity_note
  FROM content_analyses ca JOIN contents c USING (short_code)
  LEFT JOIN accounts a ON a.handle = c.account_handle
  ORDER BY c.posted_at) t""")

codes = "','".join(r["short_code"] for r in rows)
comments = q(f"""SELECT json_agg(row_to_json(t)) FROM (
  SELECT cc.short_code, cc.id, cc.author_masked, cc.body, cc.like_count, k.ai_category
  FROM content_comments cc
  LEFT JOIN comment_classifications k ON k.id = cc.id
  WHERE cc.short_code IN ('{codes}')
  ORDER BY cc.like_count DESC NULLS LAST, cc.id) t""")

handles = sorted({r["account_handle"] for r in rows})
hs = "','".join(handles)
summaries = {s["handle"]: s for s in q(
    f"SELECT json_agg(row_to_json(t)) FROM (SELECT * FROM account_summaries WHERE handle IN ('{hs}')) t")}
series = q(f"""SELECT json_agg(row_to_json(t)) FROM (
  SELECT * FROM account_content_series WHERE account_handle IN ('{hs}')
  ORDER BY posted_at, short_code) t""")
acct_llm = {a["handle"]: a for a in q(
    "SELECT json_agg(row_to_json(t)) FROM (SELECT DISTINCT ON (handle) * FROM account_analyses ORDER BY handle, analyzed_at DESC) t")}

# ---- dataset.json -------------------------------------------------------
brands, brand_ids = [], {}
dists, dist_ids = [], {}


def brand_id(name):
    if name not in brand_ids:
        brand_ids[name] = f"brand-{len(brands) + 1}"
        brands.append({"id": brand_ids[name], "name": name})
    return brand_ids[name]


def dist_id(name):
    if name not in dist_ids:
        dist_ids[name] = f"dist-{len(dists) + 1}"
        dists.append({"id": dist_ids[name], "name": name})
    return dist_ids[name]


def thumb(sc):
    local = os.path.join(FRONT, "public/thumbs", f"{sc}.jpg")
    return f"/thumbs/{sc}.jpg" if os.path.exists(local) else f"https://picsum.photos/seed/{sc}/720/900"


def avatar(handle):
    local = os.path.join(FRONT, "public/avatars", f"{handle}.jpg")
    return f"/avatars/{handle}.jpg" if os.path.exists(local) else f"https://picsum.photos/seed/{handle}/200"


fallbacks = {"adType": 0, "mainCategory": 0, "acct_copy": 0}
accounts_out = []
for h in handles:
    s = summaries.get(h, {})
    meta = next(r for r in rows if r["account_handle"] == h)
    accounts_out.append({
        "id": f"acc-{h}", "handle": h,
        "displayName": meta.get("display_name") or h,
        "profileImageUrl": avatar(h),
        "followers": meta.get("followers") or s.get("followers") or 0,
        "postsCount": s.get("posts_count") or 0,
        "followingCount": s.get("follows_count") or 0,
        "bio": s.get("biography") or "", "email": None, "externalLink": None,
    })

contents_out, deep_dives = [], {}
by_code_comments = {}
for cm in comments:
    by_code_comments.setdefault(cm["short_code"], []).append(cm)

n = len(rows)
for i, r in enumerate(rows):
    sc = r["short_code"]
    cid = f"content-{i + 1}"
    ad_type = r.get("ad_type")
    main_cat = r.get("main_category")
    if not ad_type:
        fallbacks["adType"] += 1
        ad_type = "organic"
    if not main_cat:
        fallbacks["mainCategory"] += 1
        main_cat = "makeup"
    det_brands = r.get("detected_brands") or []
    det_dists = r.get("detected_distributors") or []
    contents_out.append({
        "id": cid, "accountId": f"acc-{r['account_handle']}",
        "thumbnailUrl": thumb(sc),
        "caption": r.get("caption") or "",
        "postedAt": d(r.get("posted_at")),
        "aggregatedAt": WEEK[i * 7 // n].isoformat(),
        "contentType": r.get("content_type") or "feed",
        "mainCategory": main_cat,
        "subCategories": r.get("sub_categories") or [],
        "adType": ad_type,
        "views": r.get("views") or 0,
        "likes": r.get("likes") or 0,
        "comments": r.get("comments") or 0,
        "score": r.get("hype_score") or 0,
        "videoDuration": r.get("video_duration"),
        "originalUrl": r.get("original_url") or f"https://www.instagram.com/p/{sc}/",
        "updatedAt": "2026-07-14T00:00:00.000Z",
        "brandIds": [brand_id(b["name"]) for b in det_brands],
        "productIds": [],
        "distributorIds": [dist_id(x) for x in det_dists],
    })

    # ---- deep dive ----
    cms = by_code_comments.get(sc, [])
    counts = {c: 0 for c in COMMENT_CATS}
    for cm in cms:
        if cm.get("ai_category") in counts:
            counts[cm["ai_category"]] += 1
    total_cls = sum(counts.values())
    ratio = lambda c: round(counts[c] / total_cls, 2) if total_cls else 0.0
    views, likes, cmts = r.get("views"), r.get("likes") or 0, r.get("comments") or 0
    followers = r.get("followers") or 1
    er = round((likes + cmts) / views * 100, 2) if views else round((likes + cmts) / followers * 100, 2)
    er_base = round(float(r["recent12_avg_engagement_rate"]) * 100, 2) if r.get("recent12_avg_engagement_rate") else 0.0
    base_views = r.get("recent_reels_avg_views") or 0
    reels = [{"views": p.get("views") or 0, "postedAt": d(p.get("posted_at"))}
             for p in series if p["account_handle"] == r["account_handle"] and p.get("content_type") == "reels"]
    deep_dives[cid] = {
        "summary": r.get("ai_content_summary") or "",
        "comparison": {
            "views": {"value": views or 0, "baseline": base_views,
                      "multiple": round((views or 0) / base_views, 1) if base_views else 0.0,
                      "rankInRecent": r.get("rank_in_recent_reels") or 0,
                      "recentCount": r.get("recent_reels_count") or 0,
                      "recentReels": reels},
            "engagementRate": {"value": er, "baseline": er_base},
            "engagementQuality": {
                "likes": {"count": likes, "baselineCount": r.get("recent12_avg_like_count") or 0},
                "comments": {"count": cmts, "baselineCount": r.get("recent12_avg_comment_count") or 0}},
            "narrative": r.get("contents_pattern") or "",
        },
        "categoryContext": {
            "categoryLabel": CAT_LABEL.get(main_cat, main_cat),
            "percentile": r.get("category_top_percentile") or 0,
            "categoryAvgViews": r.get("category_avg_views") or 0,
            "sampleSize": r.get("category_sample_size") or 0,
        },
        "vlmAnalysis": {
            "brands": [{"name": b["name"], "evidence": b.get("evidence") or ""} for b in det_brands],
            "sponsoredSignal": {"level": r.get("sponsored_signal_level") or "low",
                                "reasons": r.get("sponsored_signal_reasons") or []},
            "adDisclosure": r.get("ad_disclosure") or "광고 고지 없음",
            "productCategories": r.get("detected_product_categories") or [],
            "attributes": r.get("vlm_attributes") or [],
        },
        "commentAnalysis": {
            "distribution": [{"category": c, "ratio": ratio(c)} for c in COMMENT_CATS],
            "signals": {"adAversionRate": ratio("adAware"), "friendTagRate": ratio("friendTag"),
                        "authenticity": {"grade": r.get("comment_authenticity_grade") or "normal",
                                         "note": r.get("comment_authenticity_note") or ""}},
            "insight": r.get("ai_comment_insight") or "",
        },
        "comments": [{"id": str(cm["id"]), "author": cm.get("author_masked") or "익명**",
                      "text": cm.get("body") or "", "likes": cm.get("like_count") or 0,
                      "category": cm.get("ai_category") or "etc"} for cm in cms[:30]],
    }

dataset = {"accounts": accounts_out, "contents": contents_out, "similarities": [],
           "brands": brands, "products": [], "distributors": dists}

# ---- account-reports.json ----------------------------------------------
reports = {}
for h in handles:
    s = summaries.get(h) or {}
    llm = acct_llm.get(h) or {}
    if not llm:
        fallbacks["acct_copy"] += 1
    my_series = [p for p in series if p["account_handle"] == h]
    my_contents = [(i, r) for i, r in enumerate(rows) if r["account_handle"] == h]
    bars = [{"views": p.get("views") or 0, "likes": p.get("likes") or 0,
             "comments": p.get("comments") or 0, "postedAt": d(p.get("posted_at")),
             "sponsored": bool(p.get("sponsored")), "contentType": p.get("content_type") or "feed"}
            for p in my_series]
    brand_count = {}
    for _, r in my_contents:
        for b in (r.get("detected_brands") or []):
            brand_count[b["name"]] = brand_count.get(b["name"], 0) + 1
    last_posted = s.get("last_posted_at")
    days_ago = (TODAY - date.fromisoformat(d(last_posted))).days if last_posted else None
    last_ad = s.get("last_ad_posted_at")
    ad_days = (TODAY - date.fromisoformat(d(last_ad))).days if last_ad else None
    interval = float(s["avg_interval_days"]) if s.get("avg_interval_days") is not None else None
    cat_stats = q(f"SELECT json_agg(row_to_json(t)) FROM (SELECT main_group, content_count FROM account_category_stats WHERE account_handle = '{h}' ORDER BY content_count DESC) t")
    direction = s.get("trend_direction") or "flat"
    change = s.get("trend_change_pct")
    trend_note = llm.get("trend_note") or (
        f"최근 평균이 이전 대비 {change:+d}% {'상승' if (change or 0) > 0 else '하락' if (change or 0) < 0 else '변동 없이 유지'} 흐름입니다." if change is not None else "추세 판단에 필요한 표본이 부족합니다.")
    reports[f"acc-{h}"] = {
        "report": {
            "tagline": llm.get("tagline") or "계정 LLM 분석 대기 — 수치는 집계 기준",
            "analyzedCount": s.get("analyzed_count") or len(my_contents),
            "totalPosts": s.get("posts_count") or s.get("analyzed_count") or len(my_contents),
            "summary": llm.get("summary") or
                f"분석 게시물 {s.get('analyzed_count') or len(my_contents)}개 기준 평균 좋아요 {s.get('avg_likes') or 0}개, 평균 댓글 {s.get('avg_comments') or 0}개, 참여율 {s.get('avg_er_pct') or 0}% 수준입니다. (계정 LLM 브리핑 미실행 — 결정 지표만 표시)",
            "stats": {"metric": s.get("metric") or "likes", "avgViews": s.get("avg_views") or 0,
                      "viewsPerFollower": float(s["views_per_follower"]) if s.get("views_per_follower") is not None else 0,
                      "avgEr": float(s["avg_er_pct"]) if s.get("avg_er_pct") is not None else 0,
                      "avgLikes": s.get("avg_likes") or 0, "avgComments": s.get("avg_comments") or 0},
            "trend": {"direction": direction, "note": trend_note},
            "chart": {"metric": s.get("metric") or "likes",
                      "note": llm.get("chart_note") or "", "bars": bars},
            "contentMix": {"categories": [{"label": CAT_LABEL.get(c["main_group"], c["main_group"]),
                                           "count": c["content_count"]} for c in cat_stats],
                           "traits": llm.get("traits") or []},
            "ads": {"sponsoredCount": s.get("sponsored_count") or 0,
                    "strip": [bool(p.get("sponsored")) for p in my_series],
                    "lastAdNote": (f"마지막 광고 {ad_days}일 전" if ad_days else None),
                    "comparison": None,
                    "headline": llm.get("ad_headline"),
                    "brands": [{"name": k, "count": v} for k, v in
                               sorted(brand_count.items(), key=lambda kv: -kv[1])]},
            "activity": {"lastUploadDaysAgo": days_ago, "isActive": days_ago is not None and days_ago < 14,
                         "avgIntervalDays": interval,
                         "paceNote": llm.get("pace_note") or
                             (f"{interval:.0f}일에 한 번꼴 업로드 페이스" if interval else "업로드 간격 표본 부족")},
        },
        "recentContents": [
            {"id": f"recent-acc-{h}-{j}", "thumbnailUrl": thumb(r["short_code"]),
             "caption": r.get("caption") or "", "postedAt": d(r.get("posted_at")),
             "contentType": r.get("content_type") or "feed",
             "mainCategory": r.get("main_category") or "makeup",
             "subCategories": r.get("sub_categories") or [],
             "adType": r.get("ad_type") or "organic",
             "views": r.get("views") or 0, "likes": r.get("likes") or 0,
             "comments": r.get("comments") or 0, "hypeScore": r.get("hype_score") or 0,
             "videoDuration": r.get("video_duration"),
             "originalUrl": r.get("original_url") or f"https://www.instagram.com/p/{r['short_code']}/",
             "updatedAt": "2026-07-14T00:00:00.000Z",
             "brands": [b["name"] for b in (r.get("detected_brands") or [])],
             "products": [], "distributors": r.get("detected_distributors") or [],
             "account": {"id": f"acc-{h}", "handle": h,
                         "displayName": next(a["displayName"] for a in accounts_out if a["handle"] == h),
                         "profileImageUrl": avatar(h),
                         "followers": next(a["followers"] for a in accounts_out if a["handle"] == h)}}
            for j, (_, r) in enumerate(my_contents)],
    }

for name, data in [("dataset.json", dataset), ("deep-dives.json", deep_dives),
                   ("account-reports.json", reports)]:
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"wrote {name}")

print(f"contents={len(contents_out)} accounts={len(accounts_out)} brands={len(brands)} dists={len(dists)}")
print(f"폴백: adType 기본값 {fallbacks['adType']}건, mainCategory 기본값 {fallbacks['mainCategory']}건, "
      f"계정 LLM 카피 폴백 {fallbacks['acct_copy']}건")
