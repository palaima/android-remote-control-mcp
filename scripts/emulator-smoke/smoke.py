#!/usr/bin/env python3
"""Smoke test for every Android Remote Control MCP tool on a running emulator.

Each tool is called over HTTP, and its effect is checked outside the app: with a uiautomator dump,
dumpsys, adb cat/stat/ls, ps, or a touch-probe web page. Works with one-pane (API 30, 34) and two-pane
(API 32) Settings layouts. Prepare the device with setup_device.sh first; see README.md.

Stdlib only. Prints a summary table, writes results-<serial>.json, and exits with 1 if any tool FAILs.
"""
import argparse, base64, http.server, json, os, re, shutil, subprocess, sys, threading, time
import traceback, urllib.request
import xml.etree.ElementTree as ET

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG = "com.danielealbano.androidremotecontrolmcp.gms.debug"
DEVICE_PORT = 8090
LOC = "builtin:downloads"
TEST_PKG = "dev.test.nosettext"

# Set by configure() from the command line and environment.
ADB = []          # adb command prefix, including -s <serial>
SERIAL = ""
PORT = 0          # host port forwarded to the MCP server
TOKEN = ""
URL = ""
PROBE_PORT = 0    # host port of the touch-probe page, reversed to the device
PROBE_URL = ""
BROWSER = "org.chromium.webview_shell"  # Chrome where the WebView shell is not installed
W = H = 0         # screen size in the current orientation

# Touch-probe page: reports the most fingers seen and the start/end distance of a two-finger gesture.
TOUCH_HTML = b"""<html><body style="height:2000px"><p id=o>touch-probe ready</p><script>
var mx=0,d0=null,d1=null,taps=0;
function dist(t){return Math.hypot(t[0].clientX-t[1].clientX,t[0].clientY-t[1].clientY)}
document.addEventListener('touchstart',function(e){mx=Math.max(mx,e.touches.length);if(e.touches.length==2)d0=dist(e.touches);e.preventDefault()},{passive:false});
document.addEventListener('touchmove',function(e){if(e.touches.length==2)d1=dist(e.touches);e.preventDefault()},{passive:false});
document.addEventListener('touchend',function(e){if(e.touches.length==0){taps++;document.getElementById('o').textContent='probe fingers='+mx+' d0='+Math.round(d0||0)+' d1='+Math.round(d1||0)+' n='+taps;mx=0;d0=d1=null}});
</script></body></html>"""

# ---------------------------------------------------------------- plumbing
_id = [0]
def rpc(method, params, timeout=150):
    _id[0] += 1
    body = json.dumps({"jsonrpc": "2.0", "id": _id[0], "method": method, "params": params}).encode()
    req = urllib.request.Request(URL, body, {"Authorization": f"Bearer {TOKEN}",
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    raw = urllib.request.urlopen(req, timeout=timeout).read().decode()
    if "data:" in raw[:50]:
        raw = "".join(l[5:] for l in raw.splitlines() if l.startswith("data:"))
    return json.loads(raw)

class R:
    def __init__(s, name, resp):
        s.name, s.resp = name, resp
        res = resp.get("result", {})
        s.content = res.get("content", [])
        texts = [c["text"] for c in s.content if c.get("type") == "text"]
        t = texts[-1] if texts else json.dumps(resp.get("error"))
        if t.startswith("CAUTION:"):
            t = t.split("\n", 1)[1] if "\n" in t else ""
        s.text = t
        s.err = bool(res.get("isError")) or "error" in resp or t.startswith("Error executing tool")
        s.images = [c for c in s.content if c.get("type") == "image"]
    def json(s):
        return json.loads(s.text)

CALLED = set()
def call(name, args=None, timeout=150):
    CALLED.add(name)
    try:
        return R(name, rpc("tools/call", {"name": name, "arguments": args or {}}, timeout))
    except Exception:
        # one recovery attempt: re-forward the port
        adb("forward", f"tcp:{PORT}", f"tcp:{DEVICE_PORT}")
        time.sleep(1)
        try:
            return R(name, rpc("tools/call", {"name": name, "arguments": args or {}}, timeout))
        except Exception as e:
            return R(name, {"error": {"message": f"transport: {e!r}"}})

def adb(*a, timeout=60):
    p = subprocess.run(ADB + list(a), capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr

def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)

def dump():
    """uiautomator dump -> list of node dicts (text, desc, rid, cls, bounds, ...)"""
    for _ in range(3):
        sh("uiautomator dump /sdcard/armcp_ui.xml")
        x = sh("cat /sdcard/armcp_ui.xml")
        if x.strip().startswith("<?xml"):
            break
        time.sleep(1)
    try:
        root = ET.fromstring(x[x.index("<?xml"):].split("?>", 1)[1])
    except Exception:
        return []
    nodes = []
    for n in root.iter("node"):
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
        nodes.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""), rid=n.get("resource-id", ""),
                          cls=n.get("class", ""), pkg=n.get("package", ""), bounds=b,
                          checked=n.get("checked"), checkable=n.get("checkable"), selected=n.get("selected"),
                          scrollable=n.get("scrollable")))
    return nodes

def texts(nodes):
    return [n["text"] for n in nodes if n["text"]]

def find(nodes, text=None, cls=None, desc=None):
    return [n for n in nodes if (text is None or n["text"] == text) and (cls is None or n["cls"].endswith(cls))
            and (desc is None or n["desc"] == desc)]

def center(n):
    b = n["bounds"]; return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)

def focus():
    o = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    return " ".join(o.split())

def img_ok(c):
    try:
        b = base64.b64decode(c["data"])
    except Exception as e:
        return False, f"b64 error {e}"
    kind = "PNG" if b[:8] == b"\x89PNG\r\n\x1a\n" else "JPEG" if b[:3] == b"\xff\xd8\xff" else "WEBP" if b[8:12] == b"WEBP" else None
    return (kind is not None and len(b) > 1000), f"{kind or 'unknown'} {len(b)}B {c.get('mimeType')}"

# ---------------------------------------------------------------- results
RESULTS = {}  # tool -> list of (status, evidence)
STALE = []
BUGS = []
def rec(tool, status, ev):
    RESULTS.setdefault(tool, []).append((status, ev))
    print(f"  [{status}] {tool}: {ev}", flush=True)

def check(tool, cond, ev_ok, ev_fail, r=None):
    if r is not None and r.err:
        rec(tool, "FAIL", f"{ev_fail}; tool error: {r.text[:200]}")
        return False
    rec(tool, "PASS" if cond else "FAIL", ev_ok if cond else ev_fail)
    return bool(cond)

def section(name):
    print(f"\n== {name}", flush=True)

def guard(fn):
    def w():
        section(fn.__name__)
        try:
            fn()
        except Exception:
            tb = traceback.format_exc().strip().splitlines()[-1]
            rec(f"(harness:{fn.__name__})", "FAIL", f"harness exception: {tb}")
            traceback.print_exc()
    return w

def home():
    sh("input keyevent KEYCODE_HOME"); time.sleep(1)

def start(component, extra=""):
    sh(f"am start -W {extra} -n {component}"); time.sleep(1.5)

def settings_main():
    sh("am force-stop com.android.settings")
    start("com.android.settings/.Settings")
    time.sleep(1)

# ---------------------------------------------------------------- storage perms (app UI)
def downloads_loc():
    r = call("android_list_storage_locations")
    for l in r.json():
        if l["id"] == LOC:
            return l
    return None

def downloads_perms():
    l = downloads_loc()
    return (l["allow_write"], l["allow_delete"]) if l else None

def set_downloads_perms(write, delete):
    """Toggle Downloads Allow Write/Delete in the app's Settings > Storage screen. The app has no adb/broadcast
    path for these flags, so drive its UI; switches are found by their content description
    ('Allow write on <location name>'), not by position."""
    loc = downloads_loc()
    cur = (loc["allow_write"], loc["allow_delete"])
    if cur == (write, delete):
        return True
    act = sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {PKG}").strip().splitlines()[-1]
    sh(f"am start -W -n {act}"); time.sleep(2)
    # the app restores its last screen: tap the Settings tab, then walk back to the Settings root if a
    # sub-screen is open, and scroll to the Storage entry
    lbl_desc = f"Allow write on {loc['name']}"
    for _ in range(8):
        n = dump()
        if find(n, desc=lbl_desc): break
        st = [x for x in find(n, text="Storage") if x["bounds"][3] > x["bounds"][1]]
        if st and find(n, text="Storage locations, file limits"):
            sh("input tap %d %d" % center(st[0])); time.sleep(2); continue
        if find(n, text="General"):  # Settings root, Storage below the fold
            sh(f"input swipe {W // 2} {H * 3 // 4} {W // 2} {H // 4} 400"); time.sleep(1); continue
        tabs = find(n, text="Settings")
        if tabs and not find(n, desc="Back"):
            sh("input tap %d %d" % center(tabs[-1])); time.sleep(1.5); continue
        sh("input keyevent KEYCODE_BACK"); time.sleep(1.5)
    else:
        return False
    want = {"write": write, "delete": delete}
    for i, kind in enumerate(("write", "delete")):
        if cur[i] == want[kind]: continue
        lbl = find(dump(), desc=f"Allow {kind} on {loc['name']}")
        if not lbl: return False
        sh("input tap %d %d" % center(lbl[0])); time.sleep(0.8)
    sh("input keyevent KEYCODE_BACK"); sh("input keyevent KEYCODE_BACK"); home()
    return downloads_perms() == (write, delete)

# ================================================================ tests
@guard
def t_screen_state():
    settings_main()
    r = call("android_get_screen_state", {"include_hierarchy": False})
    d = texts(dump())
    common = [t for t in d if t and t in r.text]
    check("android_get_screen_state", "com.android.settings" in r.text and len(common) >= 3,
          f"Settings window listed; {len(common)}/{len(d)} dump texts present", f"mismatch vs dump; got {r.text[:150]}", r)
    # hierarchy + pagination
    r2 = call("android_get_screen_state", {"include_hierarchy": True, "include_offscreen": True})
    m = re.search(r'cursor[^\w"]*["\s:]*([0-9a-zA-Z_-]+\.\d+)', r2.text)
    if m:
        r3 = call("android_get_screen_state", {"cursor": m.group(1)})
        check("android_get_screen_state", not r3.err, f"cursor page {m.group(1)} fetched", "cursor page failed", r3)
    else:
        rec("android_get_screen_state", "PASS" if "hierarchy" in r2.text else "FAIL", "single page (no cursor); hierarchy section " + ("present" if "hierarchy" in r2.text else "MISSING"))
    # screenshot
    r4 = call("android_get_screen_state", {"include_screenshot": True, "annotate": False})
    if r4.images:
        ok, ev = img_ok(r4.images[0]); check("android_get_screen_state", ok, f"include_screenshot: {ev}", f"bad image: {ev}", r4)
    else:
        rec("android_get_screen_state", "FAIL", f"include_screenshot returned no image content: {r4.text[:150]}")

def node_bounds(r_text):
    try:
        return [x for x in json.loads(r_text)["nodes"]]
    except Exception:
        return []

def contains(outer, inner):
    o, i = outer["bounds"], inner["bounds"]
    return o["left"] <= i["left"] and o["top"] <= i["top"] and o["right"] >= i["right"] and o["bottom"] >= i["bottom"]

def tool_node(text):
    """Tool's own node for an exact text (used only for targeting, not for verification)."""
    r = call("android_find_nodes", {"by": "text", "value": text, "exact_match": True, "visible_only": True})
    ns = node_bounds(r.text)
    return ns[0] if ns else None

def clickable_row(text):
    t = tool_node(text)
    if not t: return None
    r = call("android_find_nodes", {"by": "class_name", "value": "LinearLayout", "visible_only": True})
    cands = [n for n in node_bounds(r.text) if n.get("clickable") and contains(n, t)]
    cands.sort(key=lambda n: (n["bounds"]["right"] - n["bounds"]["left"]) * (n["bounds"]["bottom"] - n["bounds"]["top"]))
    return cands[0] if cands else None

def ncenter(n):
    b = n["bounds"]; return ((b["left"] + b["right"]) // 2, (b["top"] + b["bottom"]) // 2)

# Settings pages: title regex in the main list -> texts that appear only on that page. Works for the
# two-pane (12L) and single-pane (11, 14) layouts: titles and markers are matched in a uiautomator dump.
PAGES = {
    "Battery": (r"Battery", {"Battery Saver", "Battery saver", "Battery usage", "Battery percentage"}),
    "Sound": (r"Sound( & vibration)?", {"Media volume", "Call volume", "Ring volume", "Ring & notification volume"}),
    "Notifications": (r"Notifications|Apps & notifications", {"Notification history", "Recently opened apps", "Default apps", "Conversations"}),
    "Storage": (r"Storage", {"Free up space", "Manage storage", "Storage manager"}),
}

def settings_item(key):
    """Visible (non-zero bounds) main-list title node for PAGES[key] in the current dump."""
    rx = PAGES[key][0]
    ns = [n for n in dump() if re.fullmatch(rx, n["text"]) and n["bounds"][3] > n["bounds"][1]
          and n["bounds"][1] < H * 9 // 10]
    if ns:
        return ns[0]
    # two-pane (12L): uiautomator dump only sees the right pane; take the left-pane title from the tool
    # (targeting only; the result is still verified with the dump)
    for name in rx.replace("( & vibration)?", "|Sound & vibration").split("|"):
        t = tool_node(name)
        if t:
            b = t["bounds"]
            return dict(text=t.get("text") or name, bounds=[b["left"], b["top"], b["right"], b["bottom"]])
    return None

def page_shown(key):
    """Marker text of PAGES[key] present in the dump, or None."""
    hit = PAGES[key][1] & set(texts(dump()))
    return sorted(hit)[0] if hit else None

def apps_page():
    sh("am force-stop com.android.settings")
    sh("am start -W -a android.settings.MANAGE_APPLICATIONS_SETTINGS"); time.sleep(2)

def scroll_page():
    """Open a Settings page whose list really scrolls (verified by an adb swipe), reopen it and return
    (opener, bounds of the scrollable node). Prefers a right-pane list in two-pane layout."""
    for opener in (display_page, settings_main, apps_page):
        opener()
        sc = [n for n in dump() if n["scrollable"] == "true" and n["bounds"][3] - n["bounds"][1] > 400]
        if not sc: continue
        sc.sort(key=lambda n: (n["bounds"][0] >= W // 2, (n["bounds"][2] - n["bounds"][0]) * (n["bounds"][3] - n["bounds"][1])), reverse=True)
        b = sc[0]["bounds"]; x = (b[0] + b[2]) // 2; h = b[3] - b[1]
        before = texts(dump())
        sh(f"input swipe {x} {b[1] + h * 4 // 5} {x} {b[1] + h // 5} 400"); time.sleep(1)
        if texts(dump()) != before:
            opener()
            return opener, sc[0]
    return None, None

@guard
def t_settings_nav_and_nodes():
    settings_main()
    # click_node on the clickable row containing "Battery"
    it = settings_item("Battery")
    row = clickable_row(it["text"]) if it else None
    r = call("android_click_node", {"node_id": row["node_id"]}) if row else None
    time.sleep(1.5)
    mk = page_shown("Battery")
    if r is None:
        rec("android_click_node", "FAIL", "harness: no clickable row for Battery")
    else:
        check("android_click_node", mk, f"row {row['node_id']} -> Battery page shows '{mk}' (dump)", "page did not change to Battery", r)
    s = call("android_get_screen_state", {"include_hierarchy": False})
    STALE.append(("Settings: click_node Battery -> get_screen_state", "NEW" if mk and mk in s.text else "OLD", f"dump marker={mk!r}"))

    # selector click on a non-clickable text node (info)
    settings_main()
    it = settings_item("Sound")
    stext = it["text"] if it else "Sound"
    r = call("android_click_node", {"by": "text", "value": stext, "exact_match": True})
    rec("android_click_node", "PASS", f"(info) selector on text-only node: err={r.err}: {r.text[:110]}")

    # tap_node: Sound
    settings_main()
    r = call("android_tap_node", {"by": "text", "value": stext, "exact_match": True}); time.sleep(1.5)
    mk = page_shown("Sound")
    check("android_tap_node", mk, f"tap_node '{stext}' -> page shows '{mk}' (dump)", "Sound page not shown", r)
    s = call("android_get_screen_state", {"include_hierarchy": False})
    STALE.append(("Settings: tap_node Sound -> get_screen_state", "NEW" if mk and mk in s.text else "OLD", f"dump marker={mk!r}"))
    if mk:
        f = call("android_find_nodes", {"by": "text", "value": mk, "exact_match": True})
        STALE.append((f"Settings: tap_node Sound -> find_nodes '{mk}'", "found" if mk in f.text else "not found", ""))

    # out-of-band navigation: adb tap Notifications (or 'Apps & notifications' on 11)
    settings_main()
    it = settings_item("Notifications")
    if it:
        sh("input tap %d %d" % center(it)); time.sleep(1.5)
        mk = page_shown("Notifications")
        s = call("android_get_screen_state", {"include_hierarchy": False})
        w = call("android_wait_for_node", {"by": "text", "value": mk or "Notification history", "timeout": 3000})
        STALE.append((f"Settings: adb tap '{it['text']}' (out-of-band) -> get_screen_state",
                      "NEW" if mk and mk in s.text else "OLD", f"dump marker={mk!r}"))
        STALE.append((f"Settings: adb tap '{it['text']}' -> wait_for_node", "found" if '"found":true' in w.text else "not found", w.text[:80]))
        rec("android_wait_for_node", "PASS" if mk and '"found":true' in w.text else "FAIL", f"Settings page wait '{mk}': {w.text[:80]}")
    else:
        rec("android_wait_for_node", "FAIL", "harness: no Notifications item in Settings main list")

    # find_nodes variants
    settings_main()
    r = call("android_find_nodes", {"by": "text", "value": "Network", "exact_match": False})
    check("android_find_nodes", "Network & internet" in r.text, "by text found Network & internet", f"not found: {r.text[:150]}", r)
    rids = [n["rid"] for n in dump() if n["rid"].startswith("com.android.settings:id/") and n["bounds"][3] > n["bounds"][1]]
    rid = "com.android.settings:id/recycler_view" if "com.android.settings:id/recycler_view" in rids else (rids[0] if rids else "com.android.settings:id/recycler_view")
    r = call("android_find_nodes", {"by": "resource_id", "value": rid, "exact_match": True})
    check("android_find_nodes", rid in r.text, f"by resource_id found {rid} (from dump)", f"{rid} not found: {r.text[:150]}", r)
    r = call("android_find_nodes", {"by": "content_desc", "value": "Navigate up", "visible_only": True})
    d = [n for n in dump() if n["desc"] == "Navigate up"]
    check("android_find_nodes", ("Navigate up" in r.text) == bool(d), f"by content_desc 'Navigate up' consistent with dump ({len(d)})", f"tool {r.text[:80]} vs dump {len(d)}", r)
    r = call("android_find_nodes", {"by": "class_name", "value": "TextView"})
    nodes = node_bounds(r.text)
    check("android_find_nodes", len(nodes) > 5, f"by class_name TextView -> {len(nodes)} nodes", "few nodes", r)
    ids = [x["node_id"] for x in nodes if x.get("visible") and x.get("text")][:2]
    r = call("android_get_node_screenshots", {"node_ids": ids, "allow_scroll": False})
    if r.images:
        evs = [img_ok(c) for c in r.images]
        check("android_get_node_screenshots", all(e[0] for e in evs) and len(r.images) >= len(ids),
              f"{len(r.images)} images for {len(ids)} nodes: {[e[1] for e in evs]}", f"{len(r.images)} images for {ids}: {evs}; text: {r.text[:200]!r}", r)
    else:
        rec("android_get_node_screenshots", "FAIL", f"no image content: {r.text[:200]}")
    r = call("android_get_node_details", {"node_ids": ids})
    check("android_get_node_details", all(x in r.text for x in ids), f"details for {ids}", f"missing ids: {r.text[:150]}", r)

    # Display page (right pane, scrollable, visible to uiautomator)
    opener, sn = scroll_page()
    if not opener:
        for t in ("android_scroll_to_node", "android_scroll_node"):
            rec(t, "FAIL", "harness: no scrollable Settings page found")
        return
    sb = sn["bounds"]
    ss = call("android_get_screen_state", {"include_hierarchy": False, "include_offscreen": True})
    onscreen = set(texts(dump()))
    offs = [l.split("\t") for l in ss.text.splitlines() if l.startswith("node_") and l.split("\t")[-1].startswith("off")]
    offs = [c[2] for c in offs if len(c) > 6 and c[2] not in ("-", "") and c[2] not in onscreen
            and sb[0] <= int(c[5].split(",")[0]) < sb[2]]
    target = offs[-1] if offs else None
    rec("android_get_screen_state", "PASS" if offs else "FAIL", f"include_offscreen lists {len(offs)} off-screen texts inside the scrollable list {sb} (e.g. {offs[-1:]})")
    if target:
        r = call("android_scroll_to_node", {"by": "text", "value": target, "exact_match": True, "direction": "down"}); time.sleep(1)
        vis = [n for n in dump() if n["text"] == target]
        check("android_scroll_to_node", bool(vis), f"'{target}' visible in dump after scroll: {r.text[:90]}", f"'{target}' not in dump; tool: {r.text[:120]}", r)
    else:
        rec("android_scroll_to_node", "SKIP", "no off-screen target reported (see get_screen_state)")
    opener(); before = texts(dump())
    sel = {"by": "resource_id", "value": sn["rid"]} if sn["rid"] else {"by": "class_name", "value": sn["cls"].split(".")[-1]}
    fr = call("android_find_nodes", sel)
    def iou(tb):  # dump bounds are clipped to the visible window; tool bounds may not be
        t = [tb[k] for k in ("left", "top", "right", "bottom")]
        ix = max(0, min(t[2], sb[2]) - max(t[0], sb[0])) * max(0, min(t[3], sb[3]) - max(t[1], sb[1]))
        ua = (t[2] - t[0]) * (t[3] - t[1]) + (sb[2] - sb[0]) * (sb[3] - sb[1]) - ix
        return ix / ua if ua else 0
    sc = [x for x in node_bounds(fr.text) if x.get("scrollable") and iou(x["bounds"]) > 0.8]
    if sc:
        r = call("android_scroll_node", {"node_id": sc[0]["node_id"], "direction": "down"}); time.sleep(1)
        after = texts(dump())
        check("android_scroll_node", before != after, f"list {sel['value']} changed after scroll_node down (dump)", "dump unchanged after scroll_node", r)
        r = call("android_scroll_node", dict(sel, direction="up"))
        rec("android_scroll_node", "PASS" if not r.err or "candidates" in r.text.lower() or "more than one" in r.text.lower() else "FAIL", f"selector form {sel}: {r.text[:110]}")
    else:
        rec("android_scroll_node", "FAIL", f"no scrollable tool node with dump bounds {sb}: {fr.text[:150]}")

    # perform_node_action on nosettext guarded field ("Enter" custom action)
    start("dev.test.nosettext/.MainActivity")
    e0 = find(dump(), cls="EditText")
    if e0: sh("input tap %d %d" % center(e0[0])); time.sleep(0.5)
    fr = call("android_find_nodes", {"by": "class_name", "value": "EditText"})
    x = [x for x in node_bounds(fr.text) if x.get("actions")]
    if x:
        r = call("android_perform_node_action", {"node_id": x[0]["node_id"], "action": x[0]["actions"][0]})
        check("android_perform_node_action", not r.err, f"'{x[0]['actions'][0]}' on {x[0]['node_id']}: {r.text[:80]}", "error", r)
    else:
        rec("android_perform_node_action", "SKIP", "no node with labelled actions found")
    r = call("android_perform_node_action", {"by": "class_name", "value": "EditText", "action": "NoSuchAction"})
    rec("android_perform_node_action", "PASS" if r.err else "FAIL", f"bad selector/label -> error={r.err}: {r.text[:100]}")

def display_page():
    sh("am force-stop com.android.settings")
    sh("am start -W -a android.settings.DISPLAY_SETTINGS"); time.sleep(2)

def changed(fn):
    """Run fn; return its result and whether the uiautomator dump texts changed."""
    b = texts(dump())
    r = fn(); time.sleep(1.2)
    return r, b != texts(dump())

@guard
def t_gestures():
    settings_main()
    it = settings_item("Storage")
    x, y = center(it)
    r = call("android_tap", {"x": x, "y": y}); time.sleep(1.5)
    mk = page_shown("Storage")
    check("android_tap", mk, f"tap({x},{y}) opened Storage (dump '{mk}')", f"Storage page not shown; {texts(dump())[:8]}", r)

    opener, sn = scroll_page()
    b = sn["bounds"]; cx = (b[0] + b[2]) // 2; h = b[3] - b[1]
    y1, y2 = b[1] + h * 4 // 5, b[1] + h // 5
    r, dd = changed(lambda: call("android_scroll", {"direction": "down", "amount": "large"}))
    check("android_scroll", dd, f"scroll down changed dump ({opener.__name__})", "dump unchanged after scroll down", r)
    r, dd = changed(lambda: call("android_scroll", {"direction": "up", "amount": "small", "variance": 0}))
    check("android_scroll", dd, "scroll up changed dump", "dump unchanged after scroll up", r)
    opener()
    r, dd = changed(lambda: call("android_swipe", {"x1": cx, "y1": y1, "x2": cx, "y2": y2, "duration": 400}))
    check("android_swipe", dd, f"swipe up ({cx},{y1})->({cx},{y2}) scrolled list (dump)", "dump unchanged after swipe", r)
    opener()
    r, dd = changed(lambda: call("android_custom_gesture", {"paths": [[{"x": cx, "y": y1, "time": 0}, {"x": cx, "y": (y1 + y2) // 2, "time": 200}, {"x": cx, "y": y2, "time": 400}]]}))
    check("android_custom_gesture", dd, "single-path drag scrolled list (dump)", "dump unchanged after custom gesture", r)

    # long press / double tap / long_click_node in plain EditText containing words
    sh("am force-stop dev.test.nosettext"); start("dev.test.nosettext/.MainActivity")
    e = find(dump(), cls="EditText")
    sh("input tap %d %d" % center(e[1])); sh("input text hello%sworld" % "%s"); sh("input keyevent KEYCODE_ESCAPE"); time.sleep(0.5)
    plain = find(dump(), cls="EditText")[1]
    x, y = plain["bounds"][0] + 30, center(plain)[1]
    def toolbar():  # selection toolbar is a PopupWindow not captured by uiautomator dump
        n = len(re.findall(r"Window\{[^}]*PopupWindow", sh("dumpsys window visible")))
        return [f"{n} visible PopupWindow(s)"] if n else []
    def reset():
        sh("input keyevent KEYCODE_ESCAPE"); sh("input keyevent KEYCODE_MOVE_END"); time.sleep(0.5)
    r = call("android_long_press", {"x": x, "y": y, "duration": 1000}); time.sleep(1)
    tb = toolbar(); check("android_long_press", bool(tb), f"text-selection toolbar after long press: {tb}", "no selection toolbar after long press", r)
    reset()
    r = call("android_double_tap", {"x": x, "y": y}); time.sleep(1)
    tb = toolbar(); check("android_double_tap", bool(tb), f"selection toolbar after double tap: {tb}", "no selection toolbar after double tap", r)
    reset()
    fr = call("android_find_nodes", {"by": "class_name", "value": "EditText"})
    r = call("android_long_click_node", {"node_id": node_bounds(fr.text)[1]["node_id"]}); time.sleep(1)
    tb = toolbar(); check("android_long_click_node", bool(tb), f"selection toolbar after long_click_node: {tb}", f"no toolbar after long_click_node: {r.text[:100]}", r)
    reset()
    sh("am force-stop dev.test.nosettext")

    # touch probe page (served from the host via adb reverse) in WebView shell, or Chrome where the shell
    # cannot be installed (API 30): JS reports finger count and 2-finger start/end distance in DOM text
    sh(f"am force-stop {BROWSER}")
    sh(f"am start -W -a android.intent.action.VIEW -p {BROWSER} -d {PROBE_URL}"); time.sleep(4)
    def probe():
        t = [x for x in texts(dump()) if x.startswith("probe ")]
        m = re.search(r"fingers=(\d+) d0=(\d+) d1=(\d+)", t[0]) if t else None
        return (t[0] if t else None), (tuple(int(v) for v in m.groups()) if m else None)
    for scale, cmp in ((2.0, lambda a, b: b > a * 1.5), (0.5, lambda a, b: b < a * 0.7)):
        r = call("android_pinch", {"center_x": W // 2, "center_y": H // 2, "scale": scale, "duration": 500}); time.sleep(1)
        txt, v = probe()
        check("android_pinch", v is not None and v[0] == 2 and cmp(v[1], v[2]), f"scale {scale}: page saw '{txt}'", f"scale {scale}: page saw '{txt}'", r)
    cx, cy, step = W // 2, H // 2, W // 32  # two fingers move apart horizontally around the centre
    r = call("android_custom_gesture", {"paths": [[{"x": cx - step, "y": cy, "time": 0}, {"x": cx - 4 * step, "y": cy, "time": 300}],
                                                    [{"x": cx + step, "y": cy, "time": 0}, {"x": cx + 4 * step, "y": cy, "time": 300}]]}); time.sleep(1)
    txt, v = probe()
    check("android_custom_gesture", v is not None and v[0] == 2 and v[2] > v[1], f"2-path gesture: page saw '{txt}'", f"2-path gesture: page saw '{txt}'", r)
    sh(f"am force-stop {BROWSER}")


@guard
def t_text_and_stale():
    sh("am force-stop dev.test.nosettext"); start("dev.test.nosettext/.MainActivity")
    fr = call("android_find_nodes", {"by": "class_name", "value": "EditText"})
    nodes = fr.json()["nodes"]
    guarded, plain = nodes[0]["node_id"], nodes[1]["node_id"]
    HINT = {0: "nosettext", 1: "plain"}  # empty EditText reports its hint as text
    def field(i):
        e = find(dump(), cls="EditText")
        if len(e) <= i: return None
        return "" if e[i]["text"] == HINT[i] else e[i]["text"]
    r = call("android_type_clear_text", {"node_id": plain}); time.sleep(0.5)
    check("android_type_clear_text", field(1) == "", "plain field empty in dump", f"field={field(1)!r}", r)
    r = call("android_type_append_text", {"node_id": plain, "text": "abc"}); time.sleep(0.5)
    check("android_type_append_text", field(1) == "abc", "dump shows 'abc'", f"dump shows {field(1)!r}", r)
    r = call("android_type_insert_text", {"node_id": plain, "text": "X", "offset": 0}); time.sleep(0.5)
    check("android_type_insert_text", field(1) == "Xabc", "dump shows 'Xabc'", f"dump shows {field(1)!r}", r)
    r = call("android_type_replace_text", {"node_id": plain, "search": "abc", "new_text": "def"}); time.sleep(0.5)
    check("android_type_replace_text", field(1) == "Xdef", "dump shows 'Xdef'", f"dump shows {field(1)!r}", r)

    # ---- STALE: out-of-band change via adb input
    e = find(dump(), cls="EditText")[1]
    sh("input tap %d %d" % center(e)); sh("input keyevent KEYCODE_MOVE_END"); sh("input text ZZ")
    time.sleep(0.3)
    truth = field(1)
    want = "XdefZZ"
    s = call("android_get_screen_state", {"include_hierarchy": False})
    f1 = call("android_find_nodes", {"by": "class_name", "value": "EditText"})
    try: ftxt = f1.json()["nodes"][1]["text"]
    except Exception: ftxt = f1.text[:80]
    f2 = call("android_find_nodes", {"by": "text", "value": want, "exact_match": True})
    det = call("android_get_node_details", {"node_ids": [plain]})
    t0 = time.time(); w = call("android_wait_for_node", {"by": "text", "value": want, "timeout": 5000}); wt = time.time() - t0
    def verdict(has_new, has_old):
        return "NEW" if has_new else ("OLD" if has_old else "neither")
    STALE.append(("append via tool, then adb `input text ZZ` (dump=%r)" % truth, "-", "expected %r" % want))
    STALE.append(("  get_screen_state", verdict(want in s.text, "Xdef\t" in s.text or "Xdef" in s.text), ""))
    STALE.append(("  find_nodes class_name EditText -> node[1].text", repr(ftxt), ""))
    STALE.append(("  find_nodes text=XdefZZ exact", "found" if want in f2.text else "not found", f2.text[:100]))
    STALE.append(("  get_node_details(original id)", verdict(want in det.text, "Xdef" in det.text), det.text.replace("\t", " ")[:100]))
    STALE.append(("  wait_for_node text=XdefZZ (5s)", ("found" if '"found":true' in w.text else "not found") + f" in {wt:.1f}s", w.text[:120]))
    rec("android_wait_for_node", "PASS" if '"found":true' in w.text else "FAIL",
        f"wait for out-of-band text {want!r}: {w.text[:120]}")
    rec("android_get_node_details", "PASS" if want in det.text else "FAIL", f"after out-of-band edit: {det.text.replace(chr(9),' ')[:100]}")

    # second out-of-band change: delete 2 chars
    sh("input keyevent KEYCODE_DEL KEYCODE_DEL"); time.sleep(0.3)
    truth2 = field(1)
    s = call("android_get_screen_state", {"include_hierarchy": False})
    det = call("android_get_node_details", {"node_ids": [plain]})
    STALE.append(("adb 2x DEL (dump=%r)" % truth2, "-", ""))
    STALE.append(("  get_screen_state", "NEW" if ("XdefZZ" not in s.text and "Xdef" in s.text) else "OLD (still XdefZZ)" if "XdefZZ" in s.text else "neither", ""))
    STALE.append(("  get_node_details", "NEW" if ("XdefZZ" not in det.text and "Xdef" in det.text) else "OLD (still XdefZZ)" if "XdefZZ" in det.text else "neither", det.text.replace("\t", " ")[:80]))
    # tool-side typing after out-of-band edit must append to *real* text
    r = call("android_type_append_text", {"node_id": plain, "text": "Q"}); time.sleep(0.5)
    tr = field(1)
    check("android_type_append_text", tr == truth2 + "Q", f"append after out-of-band edit -> {tr!r}", f"append after out-of-band edit gave {tr!r}, expected {truth2+'Q'!r}", r)
    # wait_for_node timeout path
    w = call("android_wait_for_node", {"by": "text", "value": "NEVER_PRESENT_123", "timeout": 1500})
    rec("android_wait_for_node", "PASS" if ("found\":false" in w.text or w.err) else "FAIL", f"absent node: {w.text[:100]}")

    # press_key DEL / SPACE / ENTER on plain field
    before = field(1)
    r = call("android_press_key", {"key": "DEL"}); time.sleep(0.4)
    check("android_press_key", field(1) == before[:-1], f"DEL: {before!r}->{field(1)!r}", f"DEL: {before!r}->{field(1)!r}", r)
    before = field(1)
    r = call("android_press_key", {"key": "SPACE"}); time.sleep(0.4)
    check("android_press_key", field(1) == before + " ", f"SPACE: ->{field(1)!r}", f"SPACE: {before!r}->{field(1)!r}", r)
    for k in ("TAB", "ENTER"):
        r = call("android_press_key", {"key": k})
        rec("android_press_key", "PASS" if not r.err else "FAIL", f"{k}: {r.text[:60]}")

    # guarded field (refuses ACTION_SET_TEXT) -> Shizuku input fallback
    r = call("android_type_append_text", {"node_id": guarded, "text": "gq"}); time.sleep(0.6)
    g = field(0)
    check("android_type_append_text", g is not None and g.endswith("gq"), f"guarded field via fallback -> {g!r}", f"guarded field -> {g!r}", r)
    r = call("android_type_clear_text", {"node_id": guarded}); time.sleep(0.5)
    check("android_type_clear_text", field(0) == "", "guarded field cleared", f"guarded field -> {field(0)!r}", r)

    # clipboard: set via tool, paste via keyevent, verify in dump
    call("android_type_clear_text", {"node_id": plain})
    token = "clip%d" % int(time.time())
    r = call("android_set_clipboard", {"text": token})
    e = find(dump(), cls="EditText")[1]
    sh("input tap %d %d" % center(e)); sh("input keyevent 279"); time.sleep(0.6)
    check("android_set_clipboard", token in (field(1) or ""), f"pasted {token!r} into field (dump)", f"paste gave {field(1)!r}", r)
    sh("logcat -c")
    g = call("android_get_clipboard")
    deny = [l for l in sh("logcat -d -s ClipboardService").splitlines() if "Denying" in l]
    g.text += (" | logcat: " + deny[-1].split(": ", 1)[-1][:140]) if deny else ""
    check("android_get_clipboard", token in g.text, f"returned {token}", f"returned {g.text[:100]}", g)

    # dismiss keyboard
    sh("input tap %d %d" % center(e)); time.sleep(0.8)
    shown = "mInputShown=true" in sh("dumpsys input_method")
    r = call("android_dismiss_keyboard"); time.sleep(0.8)
    shown2 = "mInputShown=true" in sh("dumpsys input_method")
    check("android_dismiss_keyboard", not shown2, f"mInputShown {shown}->{shown2}", f"keyboard still shown ({shown}->{shown2})", r)
    call("android_type_clear_text", {"node_id": plain})

@guard
def t_system_nav():
    settings_main()
    r = call("android_press_home"); time.sleep(1)
    check("android_press_home", "launcher" in focus().lower(), "focus launcher", f"focus: {focus()}", r)
    settings_main()
    r = call("android_press_back"); time.sleep(1)
    check("android_press_back", "com.android.settings" not in focus().split("mFocusedApp")[0], f"left Settings; {focus()[:100]}", f"still Settings: {focus()[:120]}", r)
    settings_main()
    r = call("android_press_recents"); time.sleep(1.5)
    f = focus(); d = texts(dump())
    ok = "recents_animation" in sh("dumpsys window") or any(k in d for k in ("Clear all", "Screenshot", "Split"))
    f = f + " recents_animation=" + str("recents_animation" in sh("dumpsys window"))
    check("android_press_recents", ok, f"overview: {f[:100]} clearAll={'Clear all' in d}", f"no overview: {f[:120]}", r)
    home()
    r = call("android_open_notifications"); time.sleep(1.5)
    d = dump(); sysui = [n for n in d if n["pkg"] == "com.android.systemui"]
    ok = any("MCP" in (n["text"] + n["desc"]) or "notification" in n["rid"] for n in d) and len(sysui) > 5
    check("android_open_notifications", ok, f"shade open ({len(sysui)} systemui nodes)", "shade not detected in dump", r)
    sh("cmd statusbar collapse"); time.sleep(1)
    r = call("android_open_quick_settings"); time.sleep(1.5)
    d = texts(dump()) + [n["desc"] for n in dump()]
    ok = any(k in " ".join(d) for k in ("Internet", "Bluetooth", "Flashlight", "Auto-rotate"))
    check("android_open_quick_settings", ok, "QS tiles visible (Internet/Bluetooth/…)", "no QS tiles in dump", r)
    sh("cmd statusbar collapse"); time.sleep(1)
    r = call("android_wait_for_idle", {"timeout": 3000, "match_percentage": 95})
    check("android_wait_for_idle", "idle" in r.text.lower(), r.text[:80], r.text[:120], r)

@guard
def t_apps_intents():
    r = call("android_list_apps", {"filter": "all", "name_query": "Settings"})
    check("android_list_apps", "com.android.settings" in r.text, "name_query Settings -> com.android.settings", r.text[:150], r)
    r = call("android_list_apps", {"filter": "user"})
    third = set(re.findall(r"package:(\S+)", sh("pm list packages -3")))
    got = set(re.findall(r'"(?:package_id|packageName|package)"\s*:\s*"([^"]+)"', r.text))
    missing = [p for p in ("com.termux", TEST_PKG) if p in third and p not in r.text]
    check("android_list_apps", not missing, f"user filter has termux+nosettext ({len(got) or '?'} pkgs, pm -3 has {len(third)})", f"missing {missing}", r)
    home()
    r = call("android_open_app", {"package_id": TEST_PKG}); time.sleep(1.5)
    check("android_open_app", TEST_PKG in focus(), "focus = nosettext", f"focus: {focus()[:120]}", r)
    home(); time.sleep(5)
    adj = re.findall(r"cur=\d+", sh("dumpsys activity processes dev.test.nosettext"))[:1]
    r = call("android_close_app", {"package_id": TEST_PKG}); time.sleep(3)
    pid = sh("pidof dev.test.nosettext").strip()
    r.text += f" [oom {adj} before call]"
    check("android_close_app", pid == "", "process gone (pidof empty)", f"still running pid={pid}; tool: {r.text[:100]}", r)
    r = call("android_send_intent", {"type": "activity", "action": "android.settings.DATE_SETTINGS"}); time.sleep(1.5)
    d = texts(dump())
    check("android_send_intent", "com.android.settings" in focus() and any("time" in t.lower() for t in d), "Date & time settings opened", f"focus {focus()[:100]}", r)
    r = call("android_send_intent", {"type": "broadcast", "action": "dev.armcp.SMOKE", "extras": {"k": "v", "n": 3}})
    rec("android_send_intent", "PASS" if not r.err else "FAIL", f"broadcast with extras: {r.text[:100]}")
    home()
    r = call("android_open_uri", {"uri": "https://example.com/", "package_name": BROWSER}); time.sleep(2)
    check("android_open_uri", BROWSER in focus(), f"{BROWSER} opened", f"focus {focus()[:120]}", r)
    sh(f"am force-stop {BROWSER}")
    r = call("android_open_uri", {"uri": "content://settings/system", "mime_type": "text/plain"})
    rec("android_open_uri", "PASS", f"(info) content URI w/o handler -> err={r.err}: {r.text[:100]}")
    home()

@guard
def t_files():
    ok = set_downloads_perms(True, True)
    if not ok:
        for t in ("android_write_file", "android_append_file", "android_file_replace", "android_delete_file", "android_read_file", "android_download_from_url"):
            rec(t, "SKIP", "could not enable Downloads Allow Write/Delete via app UI")
        return
    r = call("android_list_storage_locations")
    check("android_list_storage_locations", LOC in r.text and "builtin:dcim" in r.text, "6 builtin locations incl. downloads/dcim", r.text[:100], r)
    f = "armcp_smoke.txt"
    r = call("android_write_file", {"location_id": LOC, "path": f, "content": "line1\n"})
    ls = sh(f"cat /sdcard/Download/{f}")
    check("android_write_file", ls == "line1\n", "adb cat == 'line1\\n'", f"adb cat -> {ls!r}", r)
    r = call("android_append_file", {"location_id": LOC, "path": f, "content": "line2 foo\n"})
    ls = sh(f"cat /sdcard/Download/{f}")
    check("android_append_file", ls == "line1\nline2 foo\n", "adb cat shows appended line", f"adb cat -> {ls!r}", r)
    r = call("android_file_replace", {"location_id": LOC, "path": f, "old_string": "foo", "new_string": "bar"})
    ls = sh(f"cat /sdcard/Download/{f}")
    check("android_file_replace", ls == "line1\nline2 bar\n", "adb cat shows foo->bar", f"adb cat -> {ls!r}", r)
    r = call("android_read_file", {"location_id": LOC, "path": f})
    check("android_read_file", "line2 bar" in r.text, "content read back", r.text[:100], r)
    r = call("android_read_file", {"location_id": LOC, "path": f, "offset": 2, "limit": 1})
    rec("android_read_file", "PASS" if ("line2" in r.text and "line1" not in r.text) and not r.err else "FAIL", f"offset=2 limit=1: {r.text[:80]!r}")
    r = call("android_list_files", {"location_id": LOC, "limit": 50})
    check("android_list_files", f in r.text, f"lists {f}", r.text[:120], r)
    for attempt in range(3):
        r = call("android_download_from_url", {"location_id": LOC, "path": "armcp_dl.txt", "url": "https://www.google.com/robots.txt"}, timeout=120)
        if not (r.err and "resolve host" in r.text): break
        rec("android_download_from_url", "INFO", f"attempt {attempt+1}: {r.text[:100]} (device shell ping resolves: {'bytes from' in sh('ping -c1 -W2 www.google.com')})")
        time.sleep(3)
    sz = sh("stat -c %s /sdcard/Download/armcp_dl.txt 2>&1").strip()
    if r.err and re.search(r"(?i)unknownhost|resolve|network|timeout|connect", r.text):
        rec("android_download_from_url", "SKIP", f"network unavailable: {r.text[:120]}")
    else:
        check("android_download_from_url", sz.isdigit() and int(sz) > 100, f"downloaded {sz} bytes (adb stat)", f"stat {sz}", r)
    r = call("android_share_file_via_web", {"location_id": LOC, "path": f}, timeout=60)
    if r.err and re.search(r"(?i)tunnel", r.text):
        rec("android_share_file_via_web", "SKIP", f"requires tunnel: {r.text[:140]}")
    else:
        m = re.search(r"https?://\S+", r.text)
        rec("android_share_file_via_web", "PASS" if (m and not r.err) else "FAIL", r.text[:150])
    for p in [f] + (["armcp_dl.txt"] if "No such file" not in sh("ls /sdcard/Download/armcp_dl.txt") else []):
        r = call("android_delete_file", {"location_id": LOC, "path": p})
        gone = "No such file" in sh(f"ls /sdcard/Download/{p}")
        check("android_delete_file", gone, f"{p} gone (adb ls)", f"{p} still present", r)

@guard
def t_camera():
    r = call("android_list_cameras")
    try:
        cams = r.json()
    except Exception:
        cams = []
    back = next((c["camera_id"] for c in cams if c.get("facing") == "back"), cams[0]["camera_id"] if cams else "0")
    front = next((c["camera_id"] for c in cams if c.get("facing") == "front"), back)
    check("android_list_cameras", bool(cams), f"ids back={back} front={front}: {r.text[:100]}", r.text[:100], r)
    r = call("android_list_camera_photo_resolutions", {"camera_id": back})
    check("android_list_camera_photo_resolutions", "width" in r.text, r.text[:100], r.text[:100], r)
    try:
        rs = json.loads(r.text); res = f"{rs[-1]['width']}x{rs[-1]['height']}"
    except Exception:
        res = None
    r = call("android_list_camera_video_resolutions", {"camera_id": back})
    check("android_list_camera_video_resolutions", "width" in r.text, r.text[:100], r.text[:100], r)
    home()
    r = call("android_take_camera_photo", {"camera_id": back, "quality": 80}, timeout=60)
    if r.images:
        ok, ev = img_ok(r.images[0]); check("android_take_camera_photo", ok, ev, ev, r)
    else:
        rec("android_take_camera_photo", "FAIL", f"no image (camera {back}): {r.text[:200]}")
        # diagnostics: same capture on the other camera, and camera errors in logcat
        if front != back:
            r2 = call("android_take_camera_photo", {"camera_id": front, "quality": 80}, timeout=60)
            ev = img_ok(r2.images[0])[1] if r2.images else r2.text[:150]
            rec("android_take_camera_photo", "INFO", f"retry on front camera {front}: {ev}")
        errs = [l for l in sh("logcat -d -t 400 '*:E'").splitlines() if re.search(r"(?i)camera", l)]
        rec("android_take_camera_photo", "INFO", "logcat camera errors: " + (" || ".join(e[-160:] for e in errs[-4:]) or "none"))
    args = {"camera_id": front, "location_id": LOC, "path": "armcp_photo.jpg", "quality": 80}
    if res: args["resolution"] = res
    r = call("android_save_camera_photo", args, timeout=60)
    sz = sh("stat -c %s /sdcard/Download/armcp_photo.jpg 2>&1").strip()
    check("android_save_camera_photo", sz.isdigit() and int(sz) > 1000, f"file {sz} bytes (adb stat)", f"stat {sz}", r)
    r = call("android_save_camera_video", {"camera_id": back, "location_id": LOC, "path": "armcp_video.mp4", "duration": 3, "audio": False}, timeout=90)
    sz = sh("stat -c %s /sdcard/Download/armcp_video.mp4 2>&1").strip()
    if not check("android_save_camera_video", sz.isdigit() and int(sz) > 1000, f"camera {back}: file {sz} bytes (adb stat)", f"camera {back}: stat {sz}", r) and front != back:
        call("android_delete_file", {"location_id": LOC, "path": "armcp_video.mp4"})
        r2 = call("android_save_camera_video", {"camera_id": front, "location_id": LOC, "path": "armcp_video.mp4", "duration": 3, "audio": False}, timeout=90)
        sz = sh("stat -c %s /sdcard/Download/armcp_video.mp4 2>&1").strip()
        rec("android_save_camera_video", "INFO", f"retry on front camera {front}: err={r2.err} {r2.text[:100]}; file {sz}")
    for p in ("armcp_photo.jpg", "armcp_video.mp4"):
        call("android_delete_file", {"location_id": LOC, "path": p})

@guard
def t_misc():
    # emulator mock fix (google_apis images have no last known location until a fix arrives)
    if SERIAL.startswith("emulator-"):
        adb("emu", "geo", "fix", "13.405", "52.52")
        time.sleep(2)
        r = call("android_get_location", {"fresh_fix": True}, timeout=60)
        if not (r.err and "Play Services" in r.text):
            # ground truth: the platform's last gps/fused fix (geo fix may not move the emulator GNSS position)
            m = re.search(r"Location\[(?:gps|fused) (-?[\d.]+),(-?[\d.]+)", sh("dumpsys location"))
            try:
                j = r.json(); ok = m is not None and abs(j["latitude"] - float(m.group(1))) < 0.01 and abs(j["longitude"] - float(m.group(2))) < 0.01
            except Exception:
                ok = False
            check("android_get_location", ok, f"fresh_fix=true matches dumpsys location {m.groups() if m else None}: {r.text[:100]}",
                  f"fresh_fix=true vs dumpsys {m.groups() if m else None}: {r.text[:120]}", r)
    r = call("android_get_location", {"fresh_fix": False}, timeout=60)
    if r.err and "Play Services" in r.text:
        has_gms = "com.google.android.gms" in sh("pm list packages com.google.android.gms")
        rec("android_get_location", "SKIP" if not has_gms else "FAIL", f"{r.text[:100]} (device has GMS: {has_gms}; AOSP image)")
    else:
        check("android_get_location", re.search(r"latitude", r.text) is not None, f"fresh_fix=false: {r.text[:120]}", f"fresh_fix=false: {r.text[:120]}", r)
    r = call("android_get_shared_content")
    check("android_get_shared_content", "no shared content" in r.text or "content" in r.text, r.text[:100], r.text[:100], r)

@guard
def t_shell():
    r = call("android_shell_exec", {"command": "echo hello-$FOO; id -u", "workdir": "/", "envs": {"FOO": "bar"}})
    check("android_shell_exec", "hello-bar" in r.text, f"output has hello-bar: {r.text[:100]!r}", r.text[:150], r)
    r = call("android_shell_start", {"command": "echo started; sleep 120; echo late", "workdir": "/", "timeout_minutes": 5})
    m = re.search(r'"process_id"\s*:\s*(\d+)', r.text) or re.search(r"process_id\D+(\d+)", r.text)
    pid = int(m.group(1)) if m else None
    check("android_shell_start", pid is not None, f"process_id={pid}", r.text[:150], r)
    if pid is None:
        return
    time.sleep(1)
    ps = sh("ps -A -o PID,USER,ARGS | grep 'sleep 120' | grep -v grep")
    rec("android_shell_start", "PASS" if "sleep 120" in ps else "FAIL", f"adb ps: {ps.strip()[:100] or 'no sleep process'}")
    r = call("android_shell_output", {"process_id": pid, "wait_seconds": 2})
    check("android_shell_output", "started" in r.text, r.text[:120], r.text[:150], r)
    r = call("android_shell_list")
    check("android_shell_list", str(pid) in r.text, r.text[:150], r.text[:150], r)
    r = call("android_shell_kill", {"process_id": pid}); time.sleep(1.5)
    ps = sh("ps -A | grep 'sleep 120' | grep -v grep").strip()
    check("android_shell_kill", ps == "", "sleep process gone (adb ps)", f"still running: {ps[:100]}", r)
    r = call("android_shell_list")
    rec("android_shell_list", "PASS" if not r.err else "FAIL", f"after kill: {r.text[:150]}")

def restore(orig_perms):
    section("restore")
    sh("cmd statusbar collapse")
    for p in ("armcp_smoke.txt", "armcp_dl.txt", "armcp_photo.jpg", "armcp_video.mp4"):
        if "No such file" not in sh(f"ls /sdcard/Download/{p}"):
            call("android_delete_file", {"location_id": LOC, "path": p})
    for pr in (TEST_PKG, BROWSER, "com.android.settings"):
        sh(f"am force-stop {pr}")
    sh("rm /sdcard/armcp_ui.xml")
    adb("reverse", "--remove", f"tcp:{PROBE_PORT}")
    if BROWSER == "com.android.chrome":
        sh("am clear-debug-app; rm /data/local/tmp/chrome-command-line")
    if orig_perms:
        ok = set_downloads_perms(*orig_perms)
        print(f"  storage perms restored to {orig_perms}: {ok}")
    home()
    print("  leftover files:", sh("ls /sdcard/Download").strip() or "(none)")

def screen_size():
    """Screen width and height in the current orientation, from `wm size` and the display rotation."""
    sizes = re.findall(r"(\d+)x(\d+)", sh("wm size"))  # override size, when set, is listed last
    w, h = (int(v) for v in sizes[-1])
    if re.search(r"mCurrentRotation=(ROTATION_)?(90|270|1|3)\b", sh("dumpsys window displays")):
        w, h = h, w
    return w, h

def serve_probe():
    """Serve the touch-probe page on the host and expose it to the device with adb reverse."""
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(TOUCH_HTML)))
            self.end_headers()
            self.wfile.write(TOUCH_HTML)
        def log_message(self, *a):
            pass
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", PROBE_PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    adb("reverse", f"tcp:{PROBE_PORT}", f"tcp:{PROBE_PORT}")
    return srv

def find_adb():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if sdk and os.path.isfile(os.path.join(sdk, "platform-tools", "adb")):
            return os.path.join(sdk, "platform-tools", "adb")
    path = shutil.which("adb")
    if not path:
        sys.exit("adb not found: set ANDROID_HOME or ANDROID_SDK_ROOT, or put adb on PATH")
    return path

def configure(argv):
    global ADB, SERIAL, PORT, TOKEN, URL, PROBE_PORT, PROBE_URL
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=os.environ.get("ADB_SERIAL") or os.environ.get("ANDROID_SERIAL"),
                    help="adb serial (env ADB_SERIAL or ANDROID_SERIAL; required)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("MCP_PORT", "18090")),
                    help="host port forwarded to the MCP server (env MCP_PORT; default 18090)")
    ap.add_argument("--token", default=os.environ.get("MCP_TOKEN", "smoke-test-token"),
                    help="MCP bearer token (env MCP_TOKEN; default smoke-test-token)")
    ap.add_argument("--probe-port", type=int, default=0,
                    help="host port for the touch-probe page (default 18700 + port %% 100)")
    ap.add_argument("--out", default=os.environ.get("SMOKE_OUT"),
                    help="results directory (env SMOKE_OUT; default build/emulator-smoke/<serial>)")
    a = ap.parse_args(argv)
    if not a.serial:
        ap.error("--serial (or ADB_SERIAL) is required")
    SERIAL, PORT, TOKEN = a.serial, a.port, a.token
    ADB = [find_adb(), "-s", SERIAL]
    URL = f"http://localhost:{PORT}/mcp"
    PROBE_PORT = a.probe_port or 18700 + PORT % 100
    PROBE_URL = f"http://localhost:{PROBE_PORT}/touch.html"
    return a.out or os.path.join(REPO, "build", "emulator-smoke", SERIAL)

def main(argv):
    global BROWSER, W, H
    out = configure(argv)
    os.makedirs(out, exist_ok=True)
    adb("forward", f"tcp:{PORT}", f"tcp:{DEVICE_PORT}")
    BROWSER = "org.chromium.webview_shell" if "package:" in sh("pm path org.chromium.webview_shell") else "com.android.chrome"
    if BROWSER == "com.android.chrome":  # skip Chrome first-run screens (userdebug images read this file)
        sh("echo '_ --disable-fre --no-default-browser-check --no-first-run' > /data/local/tmp/chrome-command-line")
        sh("am set-debug-app --persistent com.android.chrome")
    sh("settings put system accelerometer_rotation 0")
    W, H = screen_size()
    print(f"device: {SERIAL}  screen: {W}x{H}  browser: {BROWSER}")
    serve_probe()
    tools = [t["name"] for t in rpc("tools/list", {})["result"]["tools"]]
    orig = downloads_perms()
    print("original Downloads perms (write, delete):", orig)
    try:
        for t in (t_screen_state, t_settings_nav_and_nodes, t_gestures, t_text_and_stale, t_system_nav,
                  t_apps_intents, t_files, t_camera, t_misc, t_shell):
            t()
    finally:
        restore(orig)
    final = {}
    for t in tools:
        rs = RESULTS.get(t, [])
        st = "FAIL" if any(s == "FAIL" for s, _ in rs) else "PASS" if any(s == "PASS" for s, _ in rs) else "SKIP" if rs else "NOT RUN"
        final[t] = (st, rs)
    extra = {k: v for k, v in RESULTS.items() if k not in tools}  # harness exceptions
    uncalled = [t for t in tools if t not in CALLED]
    path = os.path.join(out, f"results-{SERIAL}.json")
    with open(path, "w") as f:
        json.dump({"serial": SERIAL, "screen": [W, H], "final": final, "extra": extra, "stale": STALE,
                   "uncalled": uncalled}, f, indent=1)
    print("\n== STALE-STATE PROBES")
    for s in STALE:
        print(" | ".join(s))
    print("\n== SUMMARY")
    width = max(len(t) for t in final)
    for t, (st, _) in final.items():
        print(f"  {t:<{width}}  {st}")
    counts = {k: sum(1 for st, _ in final.values() if st == k) for k in ("PASS", "FAIL", "SKIP", "NOT RUN")}
    print(f"\n{SERIAL}: {counts['PASS']}/{len(final)} PASS, {counts['FAIL']} FAIL, {counts['SKIP']} SKIP, "
          f"{counts['NOT RUN']} NOT RUN; harness errors: {len(extra)}; uncalled: {uncalled or 'none'}")
    print("results:", path)
    return 1 if counts["FAIL"] or counts["NOT RUN"] or extra else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
