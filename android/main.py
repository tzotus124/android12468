"""
Principles of Management - Mid-Term Prep  (desktop + Android, built with Flet)

Run on computer :  pip install flet   then   python main.py
Build the APK   :  flet build apk
Needs content.md (the study guide exported as Markdown) in the same folder.
"""
import json
import os
import random
import re
from pathlib import Path

import flet as ft

HERE = Path(__file__).resolve().parent
CONTENT = HERE / "content.md"
ICONS = getattr(ft, "Icons", None) or ft.icons
COLORS = getattr(ft, "Colors", None) or ft.colors

MCQ_RE = re.compile(
    r"^\s*\d+[.)]\s+(.*?)\s*\(a\)\s*(.*?)\s*\(b\)\s*(.*?)\s*\(c\)\s*(.*?)\s*\(d\)\s*(.*?)"
    r"\s*(?:\u2192|->)\s*\**([a-d])\**\s*$",
    re.I,
)


# ---------- storage (progress + theme) ----------
def store_path():
    try:
        base = os.environ.get("FLET_APP_STORAGE_DATA") or str(Path.home())
        return Path(base) / "pom_prep.json"
    except Exception:
        return HERE / "pom_prep.json"


def load_store():
    try:
        return json.loads(store_path().read_text("utf-8"))
    except Exception:
        return {}


def save_store(data):
    try:
        store_path().write_text(json.dumps(data), "utf-8")
    except Exception:
        pass


# ---------- content parsing ----------
def parse_sections(text):
    text = text.replace("\r\n", "\n")
    parts = re.split(r"(?m)^## ", text)
    m = re.search(r"(?m)^# (.+)$", parts[0])
    title = m.group(1).strip() if m else "Principles of Management"
    sections = [("Overview", parts[0])] if parts[0].strip() else []
    for p in parts[1:]:
        head, _, body = p.partition("\n")
        sections.append((head.strip(), "## " + head.strip() + "\n" + body))
    return title, sections


def parse_mcqs(text):
    out = []
    for line in text.replace("\\", "").splitlines():
        m = MCQ_RE.match(line)
        if m:
            g = m.groups()
            out.append({"q": g[0], "opts": list(g[1:5]), "ans": g[5].lower()})
    return out


def btn(kind, label, on_click, **kw):
    """Works across Flet versions (content= in new, text= in old)."""
    try:
        return kind(content=label, on_click=on_click, **kw)
    except TypeError:
        return kind(text=label, on_click=on_click, **kw)


# ---------- app ----------
def main(page: ft.Page):
    page.title = "POM Mid-Term Prep"
    page.padding = 0
    page.theme = ft.Theme(color_scheme_seed=COLORS.INDIGO)
    page.dark_theme = ft.Theme(color_scheme_seed=COLORS.INDIGO)

    state = load_store()
    page.theme_mode = ft.ThemeMode.DARK if state.get("dark") else ft.ThemeMode.LIGHT

    if not CONTENT.exists():
        page.add(ft.SafeArea(content=ft.Text(
            "content.md not found.\nExport the study guide as Markdown and save it "
            "as content.md next to main.py.", size=16), expand=True))
        return

    raw = CONTENT.read_text("utf-8")
    title, sections = parse_sections(raw)
    mcqs = parse_mcqs(raw)
    done = set(state.get("done", []))
    ui = {"cur": min(state.get("cur", 0), len(sections) - 1)}
    quiz = {"order": [], "i": 0, "score": 0, "done": False}

    host = ft.Container(expand=True, padding=ft.padding.symmetric(horizontal=12, vertical=6))

    def persist():
        save_store({"dark": page.theme_mode == ft.ThemeMode.DARK,
                    "done": sorted(done), "cur": ui["cur"]})

    def goto(i):
        ui["cur"] = i
        persist()
        nav.selected_index = 0
        render(0)

    # ----- study -----
    def study_view():
        i = ui["cur"]
        chips = ft.Row(
            [btn(ft.FilledButton if k == i else ft.TextButton,
                 ("\u2713 " if k in done else "") + t.split(" \u2013 ")[0][:16],
                 lambda e, k=k: goto(k))
             for k, (t, _) in enumerate(sections)],
            scroll=ft.ScrollMode.AUTO)

        def toggle(e):
            (done.add if e.control.value else done.discard)(i)
            persist()
            render(0)

        md = ft.Markdown(sections[i][1], selectable=True,
                         extension_set=ft.MarkdownExtensionSet.GITHUB_WEB)
        footer = ft.Row([
            btn(ft.OutlinedButton, "Prev", lambda e: goto(max(0, i - 1)), disabled=i == 0),
            ft.Checkbox(label="Revised", value=i in done, on_change=toggle),
            btn(ft.FilledButton, "Next", lambda e: goto(min(len(sections) - 1, i + 1)),
                disabled=i == len(sections) - 1),
        ], alignment=ft.MainAxisAlignment.SPACE_BETWEEN)
        progress = ft.ProgressBar(value=len(done) / max(1, len(sections)))
        return ft.Column([
            chips, progress,
            ft.Text(f"{len(done)}/{len(sections)} sections revised", size=12),
            ft.Column([md], scroll=ft.ScrollMode.AUTO, expand=True),
            footer], expand=True)

    # ----- search -----
    def search_view():
        results = ft.Column(scroll=ft.ScrollMode.AUTO, expand=True)

        def run(e):
            q = (e.control.value or "").strip().lower()
            results.controls.clear()
            if len(q) >= 2:
                for k, (t, body) in enumerate(sections):
                    hits = [ln for ln in body.splitlines() if q in ln.lower()]
                    if hits:
                        snip = re.sub(r"[*#|]", "", hits[0]).strip()[:140]
                        results.controls.append(ft.ListTile(
                            title=ft.Text(f"{t}  ({len(hits)} matches)", weight=ft.FontWeight.BOLD),
                            subtitle=ft.Text(snip), on_click=lambda e, k=k: goto(k)))
                if not results.controls:
                    results.controls.append(ft.Text("No matches."))
            page.update()

        return ft.Column([
            ft.TextField(hint_text="Search the whole guide (e.g. Fayol, MBO, PESTLE)",
                         prefix_icon=ICONS.SEARCH, on_change=run),
            results], expand=True)

    # ----- quiz -----
    def quiz_view():
        if not mcqs:
            return ft.Text("No MCQs found in content.md.")
        if not quiz["order"]:
            quiz["order"] = random.sample(range(len(mcqs)), len(mcqs))
            quiz["i"], quiz["score"] = 0, 0

        def restart(e):
            quiz["order"] = []
            render(2)

        if quiz["i"] >= len(quiz["order"]):
            return ft.Column([
                ft.Text("Quiz finished", size=24, weight=ft.FontWeight.BOLD),
                ft.Text(f"Score: {quiz['score']} / {len(mcqs)}", size=20),
                btn(ft.FilledButton, "Restart", restart)], expand=True)

        q = mcqs[quiz["order"][quiz["i"]]]
        fb = ft.Text("", size=16, weight=ft.FontWeight.BOLD)
        nxt = btn(ft.FilledButton, "Next", lambda e: (quiz.update(i=quiz["i"] + 1), render(2)),
                  visible=False)
        locked = {"v": False}

        def pick(letter):
            if locked["v"]:
                return
            locked["v"] = True
            if letter == q["ans"]:
                quiz["score"] += 1
                fb.value, fb.color = "Correct!", COLORS.GREEN
            else:
                fb.value, fb.color = f"Wrong. Correct answer: {q['ans'].upper()}", COLORS.RED
            nxt.visible = True
            page.update()

        opts = [btn(ft.OutlinedButton, f"{L.upper()}.  {t}", lambda e, L=L: pick(L))
                for L, t in zip("abcd", q["opts"])]
        return ft.Column([
            ft.Text(f"Question {quiz['i'] + 1} of {len(mcqs)}   |   Score {quiz['score']}", size=12),
            ft.Text(q["q"], size=20, weight=ft.FontWeight.BOLD), *opts, fb, nxt],
            scroll=ft.ScrollMode.AUTO, expand=True, spacing=10)

    views = [study_view, search_view, quiz_view]

    def render(idx):
        host.content = views[idx]()
        page.update()

    def toggle_theme(e):
        page.theme_mode = (ft.ThemeMode.LIGHT if page.theme_mode == ft.ThemeMode.DARK
                           else ft.ThemeMode.DARK)
        persist()
        page.update()

    page.appbar = ft.AppBar(title=ft.Text(title, size=16),
                            actions=[ft.IconButton(ICONS.BRIGHTNESS_6, on_click=toggle_theme)])
    nav = ft.NavigationBar(
        selected_index=0,
        on_change=lambda e: render(int(e.control.selected_index)),
        destinations=[
            ft.NavigationBarDestination(icon=ICONS.MENU_BOOK, label="Study"),
            ft.NavigationBarDestination(icon=ICONS.SEARCH, label="Search"),
            ft.NavigationBarDestination(icon=ICONS.QUIZ, label="Quiz"),
        ])
    page.navigation_bar = nav
    page.add(ft.SafeArea(content=host, expand=True))
    render(0)


if __name__ == "__main__":
    if hasattr(ft, "run"):
        ft.run(main)
    else:
        ft.app(target=main)
