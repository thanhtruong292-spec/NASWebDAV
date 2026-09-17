$ErrorActionPreference = "Stop"

# PATHS
$UserHome         = $env:USERPROFILE
$SharedSkillsRoot = "$UserHome\.ai-skills"
$ClaudeMd         = "$UserHome\.claude\CLAUDE.md"
$GeminiMd         = "$UserHome\.gemini\config\AGENTS.md"
$GeminiSkl        = "$UserHome\.gemini\config\skills"
$CodexMd          = "$UserHome\.codex\instructions.md"
$CursorMd         = "$UserHome\.cursor\rules"

# SKILLS TO INSTALL
$Skills = @(
    @{ name="code-review";                   url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/code-review/SKILL.md" },
    @{ name="diagnosing-bugs";               url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/diagnosing-bugs/SKILL.md" },
    @{ name="tdd";                           url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/tdd/SKILL.md" },
    @{ name="grill-with-docs";              url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/grill-with-docs/SKILL.md" },
    @{ name="grill-me";                      url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/productivity/grill-me/SKILL.md" },
    @{ name="to-spec";                       url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/to-spec/SKILL.md" },
    @{ name="improve-codebase-architecture"; url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/improve-codebase-architecture/SKILL.md" },
    @{ name="request-refactor-plan";         url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/request-refactor-plan/SKILL.md" },
    @{ name="setup-matt-pocock-skills";      url="https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/setup-matt-pocock-skills/SKILL.md" }
)

# MANDATORY RULES (injected into every agent config)
$Marker = "Matt Pocock Skills -- GLOBAL"
$RulesBlock = @"

# $Marker
# Source: https://github.com/mattpocock/skills
# Installed: $(Get-Date -Format 'yyyy-MM-dd')

## Skills Location
All skills are installed at: $SharedSkillsRoot

## Mandatory Skill Usage

| Situation | Skill |
|-----------|-------|
| Code review / PR / branch | code-review |
| Debug / bug / error | diagnosing-bugs |
| Write tests / TDD | tdd |
| Plan / design a feature | grill-with-docs or grill-me |
| Create spec from conversation | to-spec |
| Improve codebase architecture | improve-codebase-architecture |
| Refactor | request-refactor-plan |

## Hard Rules (cannot be skipped)

1. MUST read the relevant SKILL.md before performing the task
2. MUST use code-review (2-axis: Standards + Spec) when user asks for review
3. MUST use diagnosing-bugs when debugging -- no guessing
4. If docs/agents/issue-tracker.md is missing: run setup-matt-pocock-skills first
5. Never skip a skill because a task seems simple

"@

# HELPERS
function Ensure-Dir([string]$p) {
    if (-not (Test-Path $p)) {
        New-Item -ItemType Directory -Path $p -Force | Out-Null
        Write-Host "  + mkdir: $p" -ForegroundColor DarkGray
    }
}

function Inject-Block([string]$file, [string]$block) {
    Ensure-Dir (Split-Path $file -Parent)
    $existing = if (Test-Path $file) { Get-Content $file -Raw -Encoding UTF8 } else { "" }
    if ($existing -match [regex]::Escape($Marker)) {
        Write-Host "  already configured: $file" -ForegroundColor Gray
    } else {
        $updated = $existing.TrimEnd() + "`n" + $block
        [System.IO.File]::WriteAllText($file, $updated, [System.Text.Encoding]::UTF8)
        Write-Host "  configured: $file" -ForegroundColor Green
    }
}

# ============================================================
Write-Host ""
Write-Host "Matt Pocock Skills -- Multi-Agent Global Install" -ForegroundColor Cyan
Write-Host "Targets: Claude Code  |  Gemini/Antigravity  |  Codex  |  Cursor" -ForegroundColor Cyan
Write-Host ""

# STEP 1 -- Download skills to shared location
Write-Host "[1/4] Downloading $($Skills.Count) skills to $SharedSkillsRoot ..." -ForegroundColor Yellow
Ensure-Dir $SharedSkillsRoot
$ok = 0; $fail = 0
foreach ($s in $Skills) {
    $dest = "$SharedSkillsRoot\$($s.name)\SKILL.md"
    Ensure-Dir "$SharedSkillsRoot\$($s.name)"
    try {
        $resp = Invoke-WebRequest -Uri $s.url -UseBasicParsing -TimeoutSec 30
        [System.IO.File]::WriteAllText($dest, $resp.Content, [System.Text.Encoding]::UTF8)
        Write-Host "  [ok] $($s.name)" -ForegroundColor Green
        $ok++
    } catch {
        Write-Host "  [FAIL] $($s.name): $_" -ForegroundColor Red
        $fail++
    }
}
Write-Host "  Result: $ok ok, $fail failed" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Yellow" })

# STEP 2 -- Gemini / Antigravity
Write-Host ""
Write-Host "[2/4] Gemini CLI / Antigravity (~\.gemini\config\) ..." -ForegroundColor Yellow
Ensure-Dir $GeminiSkl
foreach ($s in $Skills) {
    $link   = "$GeminiSkl\$($s.name)"
    $target = "$SharedSkillsRoot\$($s.name)"
    if (-not (Test-Path $link)) {
        try {
            cmd /c mklink /J "$link" "$target" 2>$null | Out-Null
            if (Test-Path $link) {
                Write-Host "  linked: $($s.name)" -ForegroundColor Green
            } else {
                Copy-Item -Path $target -Destination $link -Recurse -Force
                Write-Host "  copied: $($s.name)" -ForegroundColor Green
            }
        } catch {
            Copy-Item -Path $target -Destination $link -Recurse -Force
            Write-Host "  copied: $($s.name)" -ForegroundColor Gray
        }
    } else {
        Write-Host "  exists: $($s.name)" -ForegroundColor Gray
    }
}
Inject-Block $GeminiMd $RulesBlock

# STEP 3 -- Claude Code
Write-Host ""
Write-Host "[3/4] Claude Code (~\.claude\CLAUDE.md) ..." -ForegroundColor Yellow
$claudeExtra = "`n## Skills Path for Claude Code`nRead SKILL.md from: $SharedSkillsRoot\<skill-name>\SKILL.md`n"
Inject-Block $ClaudeMd ($RulesBlock + $claudeExtra)

# STEP 4 -- Codex + Cursor
Write-Host ""
Write-Host "[4/4] Codex (~\.codex\) and Cursor (~\.cursor\) ..." -ForegroundColor Yellow
$codexExtra  = "`n## Skills Path for Codex`nRead SKILL.md from: $SharedSkillsRoot\<skill-name>\SKILL.md`n"
$cursorExtra = "`n## Skills Path for Cursor`nRead SKILL.md from: $SharedSkillsRoot\<skill-name>\SKILL.md`n"
Inject-Block $CodexMd  ($RulesBlock + $codexExtra)
Inject-Block $CursorMd ($RulesBlock + $cursorExtra)

# BONUS -- issue-tracker.md for current git repo
$gitRemote = (git remote get-url origin 2>$null)
if ($gitRemote) {
    $slug = $gitRemote -replace ".*github\.com[:/](.+?)(\.git)?$", '$1'
    $itFile = "docs\agents\issue-tracker.md"
    if (-not (Test-Path $itFile)) {
        Ensure-Dir "docs\agents"
        $itContent = "# Issue Tracker`n`nThis repo uses **GitHub Issues** on ``$slug``.`n`n" +
                     "## CLI Commands`n``````bash`n" +
                     "gh issue list   --repo $slug`n" +
                     "gh issue view   <n> --repo $slug`n" +
                     "gh issue create --repo $slug --title `"...`" --body `"...`"`n" +
                     "gh issue close  <n> --repo $slug`n``````"
        [System.IO.File]::WriteAllText($itFile, $itContent, [System.Text.Encoding]::UTF8)
        Write-Host ""
        Write-Host "  + Created: $itFile  (repo: $slug)" -ForegroundColor Green
    }
}

# DONE
Write-Host ""
Write-Host "====================================================" -ForegroundColor Green
Write-Host "  [DONE] Installation Complete" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Green
Write-Host "  Shared skills : $SharedSkillsRoot" -ForegroundColor White
Write-Host "  Claude Code   : $ClaudeMd" -ForegroundColor White
Write-Host "  Gemini/Antig  : $GeminiMd" -ForegroundColor White
Write-Host "  Codex         : $CodexMd" -ForegroundColor White
Write-Host "  Cursor        : $CursorMd" -ForegroundColor White
Write-Host "====================================================" -ForegroundColor Green
Write-Host "  Restart your AI agent / IDE to apply changes." -ForegroundColor Yellow
Write-Host "====================================================" -ForegroundColor Green
Write-Host ""
