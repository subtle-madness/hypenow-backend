'use strict';

/**
 * 인스타 댓글 doc_id 자동 갱신자 (one-shot)
 *
 * 설계: docs/superpowers/specs/2026-09-01-ig-comment-docid-refresher-design.md §3
 *
 * 흐름: 헤드리스 Playwright(로그아웃, 레지덴셜 프록시 geo:kr)로 공개 게시물을 열어
 * 댓글 페이징 GraphQL 요청(PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery)의
 * doc_id를 캡처 → 같은 브라우저 컨텍스트로 그 doc_id를 실제 페이징 콜에 태워 검증
 * (200 + edges>0 + errors 없음) → 검증 통과 시에만 monitoring DB app_setting에 upsert.
 *
 * 캡처·검증 실패 시 app_setting은 건드리지 않는다(기존 값 유지) — 자체크롤은 계속
 * 기존 doc_id로 동작하고, 다음 크론 주기(주 2회)에 재시도된다.
 *
 * 자격증명(프록시 URL, DB 비밀번호)은 어떤 로그·에러 메시지에도 출력하지 않는다.
 */

const { chromium } = require('playwright');
const { Client } = require('pg');

// ---------- 설정 ----------

// 캡처 타깃 후보 — doc_id는 게시물 무관 전역값이라 어느 공개 게시물에서 잡아도 동일하다.
// 한 게시물이 삭제·비활성화돼도 다음 게시물로 순차 폴백한다.
// ⚠️ DcOX3hWFiey(nasa)만 09-01 실측으로 캡처 성공이 확인된 항목이다. 나머지 폴백 후보는
// 로컬 e2e(스펙 §5, 실 프록시 필요) 시점에 실측 검증 후 추가할 것 — 미검증 shortcode를
// 임의로 채워 넣지 않았다(존재하지 않는 게시물은 캡처 실패로 다음 URL로 넘어갈 뿐이라
// 안전하지만, 검증되지 않은 값을 정본인 양 커밋하는 쪽이 더 나쁘다는 판단).
const DEFAULT_TARGET_URLS = ['https://www.instagram.com/p/DcOX3hWFiey/'];

const FRIENDLY_NAME_EXPECTED = 'PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery';
const GRAPHQL_URL = 'https://www.instagram.com/api/graphql';
const APP_ID = '936619743392459';
const DESKTOP_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36';

const SCROLL_ATTEMPTS = 10;
const SCROLL_WAIT_MS = 1000;
const NAV_TIMEOUT_MS = 45000;

const KEY_DOC_ID = 'ig-source.comment-doc-id';
const KEY_FRIENDLY_NAME = 'ig-source.comment-friendly-name';
const KEY_REFRESHED_AT = 'ig-source.comment-doc-id-refreshed-at';

const SHORTCODE_ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

// ---------- 유틸 ----------

function log(...args) {
  console.log('[docid-refresher]', ...args);
}

function envOr(name, fallback) {
  const v = process.env[name];
  return v === undefined || v === '' ? fallback : v;
}

function isTrue(v) {
  return String(v).toLowerCase() === 'true';
}

// 프록시 URL 파싱 — 실패 메시지에 원본 문자열을 절대 포함하지 않는다(자격증명 비노출).
function parseProxyUrl(raw) {
  const m = raw.trim().match(/^https?:\/\/([^:]+):([^@]+)@(.+)$/);
  if (!m) throw new Error('proxy url 파싱 실패');
  const [, user, pass, hostport] = m;
  return { user, pass, hostport };
}

function extractLsd(html) {
  const m = html.match(/"LSD",\[\],\{"token":"([^"]+)"/);
  return m ? m[1] : null;
}

// key 뒤의 균형 잡힌(nested) JSON 객체를 문자열로 추출 — verify_comment_paging.py의
// balanced_object_after와 동일 알고리즘(문자열 내부의 중괄호는 무시).
function balancedObjectAfter(html, key) {
  const marker = `"${key}":`;
  const idx = html.indexOf(marker);
  if (idx < 0) return null;
  const start = html.indexOf('{', idx + marker.length);
  if (start < 0) return null;
  let inString = false;
  let escaped = false;
  let depth = 0;
  for (let i = start; i < html.length; i++) {
    const c = html[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (c === '\\') {
        escaped = true;
      } else if (c === '"') {
        inString = false;
      }
      continue;
    }
    if (c === '"') {
      inString = true;
    } else if (c === '{') {
      depth++;
    } else if (c === '}') {
      depth--;
      if (depth === 0) {
        return html.slice(start, i + 1);
      }
    }
  }
  return null;
}

function mediaIdFromShortcode(shortcode) {
  let n = 0n;
  for (const ch of shortcode) {
    const idx = SHORTCODE_ALPHABET.indexOf(ch);
    if (idx < 0) throw new Error('shortcode 문자 해석 실패');
    n = n * 64n + BigInt(idx);
  }
  return n.toString();
}

function extractShortcodeFromUrl(url) {
  const m = url.match(/\/(?:p|reel)\/([^/?]+)\/?/);
  return m ? m[1] : null;
}

// page1 SSR HTML에서 검증 페이징에 필요한 값들을 뽑는다(verify_comment_paging.py 이식).
function extractPage1(html, postUrl) {
  const lsd = extractLsd(html);

  let endCursor = null;
  let edgesCount = 0;
  const ccJson = balancedObjectAfter(html, 'comments_connection');
  if (ccJson) {
    try {
      const cc = JSON.parse(ccJson);
      edgesCount = Array.isArray(cc.edges) ? cc.edges.length : 0;
      endCursor = cc.page_info ? cc.page_info.end_cursor : null;
    } catch (e) {
      log('comments_connection JSON 파싱 실패:', e.message);
    }
  }

  const idMatch = html.match(/xig_polaris_media"\s*:\s*\{\s*"id"\s*:\s*"([^"]+)"/);
  let mediaId = idMatch ? idMatch[1] : null;
  if (!mediaId) {
    const shortcode = extractShortcodeFromUrl(postUrl);
    if (shortcode) {
      try {
        mediaId = mediaIdFromShortcode(shortcode);
      } catch (e) {
        log('shortcode → media_id 계산 실패:', e.message);
      }
    }
  }

  return { lsd, endCursor, edgesCount, mediaId };
}

async function closeSignupModal(page) {
  const closeBtn =
    (await page.$('svg[aria-label="닫기"]')) || (await page.$('[aria-label="닫기"]'));
  if (closeBtn) {
    await closeBtn.click({ force: true }).catch(() => {});
  } else {
    await page.keyboard.press('Escape').catch(() => {});
  }
}

async function upsertSetting(client, key, value) {
  await client.query(
    `INSERT INTO app_setting (key, value) VALUES ($1, $2)
     ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`,
    [key, value],
  );
}

// ---------- 캡처 ----------

// 타깃 URL을 순차 시도하며 페이징 GraphQL 요청(doc_id 포함)을 가로챈다.
// 반환: { captured, usedUrl, pageHtml } — 실패 시 captured=null.
async function captureDocId(context, targetUrls) {
  let captured = null;

  context.on('request', (request) => {
    if (captured) return;
    if (request.method() !== 'POST' || !request.url().includes('/api/graphql')) return;

    const postData = request.postData() || '';
    const params = new URLSearchParams(postData);
    const friendly = params.get('fb_api_req_friendly_name');
    if (friendly !== FRIENDLY_NAME_EXPECTED) return;

    const docId = params.get('doc_id');
    if (!docId) return;

    let variables = null;
    const variablesRaw = params.get('variables');
    if (variablesRaw) {
      try {
        variables = JSON.parse(variablesRaw);
      } catch (e) {
        // variables 파싱 실패는 치명적이지 않음 — page1 폴백으로 media_id/after를 구한다.
      }
    }

    captured = { docId, friendly, variables };
  });

  let usedUrl = null;
  let pageHtml = null;

  for (const url of targetUrls) {
    if (captured) break;
    log(`캡처 시도: ${url}`);
    let page;
    try {
      page = await context.newPage();
      await page.goto(url, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT_MS });
      await page.waitForTimeout(2000);

      // page1 SSR HTML은 초기 로드 시점에 이미 완성돼 있다(스크롤로 바뀌지 않음).
      const html = await page.content();

      await closeSignupModal(page);
      await page.waitForTimeout(1500);

      for (let i = 0; i < SCROLL_ATTEMPTS && !captured; i++) {
        await page.mouse.wheel(0, 1200);
        await page.waitForTimeout(SCROLL_WAIT_MS);
      }

      if (captured) {
        usedUrl = url;
        pageHtml = html;
        log(`캡처 성공: ${url}`);
      } else {
        log(`페이징 요청 미발화: ${url}`);
      }
    } catch (e) {
      log(`캡처 시도 실패(${url}): ${e.message}`);
    } finally {
      if (page) await page.close().catch(() => {});
    }
  }

  return { captured, usedUrl, pageHtml };
}

// ---------- 검증 ----------

// 캡처된 doc_id로 실제 페이징 콜 1회를 쳐서 유효성을 확인한다.
// 합격 조건: HTTP 200 AND edges.length > 0 AND top-level errors 없음.
async function verifyDocId(context, { captured, usedUrl, pageHtml }) {
  const page1 = extractPage1(pageHtml, usedUrl);

  const mediaId =
    (captured.variables && captured.variables.media_id && String(captured.variables.media_id)) ||
    page1.mediaId;
  const afterCursor =
    (captured.variables && captured.variables.after) || page1.endCursor;
  const lsd = page1.lsd;

  if (!mediaId || !afterCursor || !lsd) {
    log(
      `검증 불가 — 필요값 누락(media_id=${!!mediaId}, after=${!!afterCursor}, lsd=${!!lsd})`,
    );
    return { verified: false };
  }

  const cookies = await context.cookies();
  const csrftoken = cookies.find((c) => c.name === 'csrftoken');
  if (!csrftoken) {
    log('검증 불가 — csrftoken 쿠키 없음');
    return { verified: false };
  }

  const variables = { media_id: mediaId, after: afterCursor, first: 10 };
  const formBody = new URLSearchParams({
    lsd,
    fb_api_req_friendly_name: captured.friendly,
    doc_id: captured.docId,
    variables: JSON.stringify(variables),
  }).toString();

  let resp;
  try {
    resp = await context.request.post(GRAPHQL_URL, {
      // GraphQL로 인식시키려면 브라우저 fetch가 자동으로 붙이는 헤더를 수동 재현해야 한다.
      // context.request는 이를 안 붙여서, 빠지면 IG가 navigation으로 오인해 전체 페이지
      // HTML(200·text/html)을 돌려준다(로컬 e2e 실측). Sec-Fetch-*/Origin/UA가 핵심.
      headers: {
        'x-ig-app-id': APP_ID,
        'x-fb-lsd': lsd,
        'X-CSRFToken': csrftoken.value,
        'X-FB-Friendly-Name': captured.friendly,
        'Content-Type': 'application/x-www-form-urlencoded',
        'User-Agent': DESKTOP_UA,
        Origin: 'https://www.instagram.com',
        Referer: usedUrl,
        'Sec-Fetch-Site': 'same-origin',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Dest': 'empty',
      },
      data: formBody,
      timeout: 30000,
    });
  } catch (e) {
    log('검증 요청 실패:', e.message);
    return { verified: false };
  }

  const status = resp.status();
  let json = null;
  let bodyText = '';
  try {
    // 정상 GraphQL 응답은 content-type이 application/json이 아니라 text/javascript다(실측) —
    // resp.json()의 content-type 검사에 걸리지 않게 text로 받아 직접 파싱한다. 실패 시 본문이
    // JSON이 아니라는 뜻(HTML 로그인벽·페이지 등)이므로 content-type·길이로 원인을 남긴다.
    bodyText = await resp.text();
    json = JSON.parse(bodyText);
  } catch (e) {
    log(`검증 응답 비-JSON: ct=${resp.headers()['content-type']} len=${bodyText.length}`);
  }

  const edges = json && json.data && json.data.xig_polaris_media
    ? json.data.xig_polaris_media.comments_connection && json.data.xig_polaris_media.comments_connection.edges
    : null;
  const edgesLen = Array.isArray(edges) ? edges.length : 0;
  const hasErrors = !!(json && Array.isArray(json.errors) && json.errors.length > 0);

  const verified = status === 200 && edgesLen > 0 && !hasErrors;
  log(`검증 결과: status=${status} edges=${edgesLen} errors=${hasErrors} → ${verified ? '합격' : '불합격'}`);

  return { verified, edgesLen };
}

// ---------- 반영 ----------

async function upsertToDb({ docId, friendlyName }) {
  const client = new Client({
    host: envOr('MONITORING_DB_HOST', 'postgres'),
    port: Number(envOr('MONITORING_DB_PORT', '5432')),
    database: envOr('MONITORING_DB_NAME', 'monitoring'),
    user: process.env.MONITORING_DB_USER,
    password: process.env.MONITORING_DB_PASSWORD,
  });

  await client.connect();
  try {
    await client.query('BEGIN');

    const prev = await client.query('SELECT value FROM app_setting WHERE key = $1', [KEY_DOC_ID]);
    const oldDocId = prev.rows[0] ? prev.rows[0].value : null;

    await upsertSetting(client, KEY_DOC_ID, docId);
    await upsertSetting(client, KEY_FRIENDLY_NAME, friendlyName);
    await upsertSetting(client, KEY_REFRESHED_AT, new Date().toISOString());

    await client.query('COMMIT');

    if (oldDocId === docId) {
      log(`unchanged(doc_id 회전 없음): ${docId}`);
    } else {
      log(`rotated: ${oldDocId || '(없음)'} → ${docId}`);
    }
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    await client.end().catch(() => {});
  }
}

// ---------- 메인 ----------

async function run() {
  const startedAt = Date.now();

  const proxyRaw = process.env.DATAIMPULSE_RESIDENTIAL_PROXY_URL;
  if (!proxyRaw) {
    log('DATAIMPULSE_RESIDENTIAL_PROXY_URL 미설정');
    return 1;
  }

  let proxy;
  try {
    proxy = parseProxyUrl(proxyRaw);
  } catch (e) {
    log(e.message); // "proxy url 파싱 실패"만 — 원본 문자열은 절대 포함하지 않는다.
    return 1;
  }
  const geoUser = `${proxy.user}__cr.kr`;

  const targetUrls = process.env.DOCID_TARGET_URLS
    ? process.env.DOCID_TARGET_URLS.split(',').map((s) => s.trim()).filter(Boolean)
    : DEFAULT_TARGET_URLS;

  const dryRun = isTrue(process.env.DRY_RUN);

  log(`캡처 대상 ${targetUrls.length}건, DRY_RUN=${dryRun}`);

  const browser = await chromium.launch({
    headless: true,
    proxy: { server: `http://${proxy.hostport}`, username: geoUser, password: proxy.pass },
    args: ['--headless=new'],
  });

  try {
    const context = await browser.newContext({
      userAgent: DESKTOP_UA,
      viewport: { width: 1400, height: 1000 },
      locale: 'ko-KR',
      timezoneId: 'Asia/Seoul',
    });

    const captureResult = await captureDocId(context, targetUrls);
    if (!captureResult.captured) {
      log('전 타깃에서 doc_id 캡처 실패');
      return 1;
    }

    const { verified, edgesLen } = await verifyDocId(context, captureResult);
    if (!verified) {
      log('doc_id 검증 실패 — app_setting 미반영');
      return 1;
    }

    const { docId, friendly } = captureResult.captured;
    const elapsedSec = ((Date.now() - startedAt) / 1000).toFixed(1);
    log(
      `캡처·검증 성공: url=${captureResult.usedUrl} doc_id=${docId} friendly=${friendly} edges=${edgesLen} 소요=${elapsedSec}s`,
    );

    if (dryRun) {
      log(`[dry-run] would upsert doc_id=${docId} friendly_name=${friendly}`);
      return 0;
    }

    await upsertToDb({ docId, friendlyName: friendly });
    return 0;
  } finally {
    await browser.close().catch(() => {});
  }
}

run()
  .then((code) => {
    process.exit(code);
  })
  .catch((e) => {
    // stack 대신 message만 — 자격증명 등 민감정보가 stack에 섞여 나올 가능성 차단.
    log('실패:', e.message);
    process.exit(1);
  });
