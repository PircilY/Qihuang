(() => {
    const system = window.matchMedia("(prefers-color-scheme: dark)");
    const choices = ["system", "light", "dark"];
    let choice = "system";
    try {
        const saved = localStorage.getItem("qihuang-theme");
        if (choices.includes(saved)) choice = saved;
    } catch {
        // Storage can be unavailable in private or restricted browsing.
    }
    function apply() {
        const theme = choice === "system" ? (system.matches ? "dark" : "light") : choice;
        document.documentElement.dataset.theme = theme;
        document.documentElement.dataset.themePreference = choice;
        document.querySelectorAll("button[data-theme-choice]").forEach(button =>
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === choice)));
        const meta = document.querySelector('meta[name="theme-color"]');
        if (meta && document.body) meta.content = getComputedStyle(document.documentElement).getPropertyValue("--canvas").trim();
    }
    window.uiTheme = {
        set(next) {
            if (!choices.includes(next)) return;
            choice = next;
            try {localStorage.setItem("qihuang-theme", choice);} catch {}
            apply();
        }
    };
    system.addEventListener("change", apply);
    document.addEventListener("DOMContentLoaded", apply);
    apply();
})();
