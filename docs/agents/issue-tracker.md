# Issue Tracker

This repo uses **GitHub Issues** as its issue tracker.

- **Repo**: `thanhtruong292-spec/NASWebDAV`
- **Remote URL**: `https://github.com/thanhtruong292-spec/NASWebDAV.git`
- **CLI tool**: `gh` (GitHub CLI)

## How to interact with issues

### List open issues
```bash
gh issue list --repo thanhtruong292-spec/NASWebDAV
```

### View a specific issue
```bash
gh issue view <number> --repo thanhtruong292-spec/NASWebDAV
```

### Create an issue
```bash
gh issue create --repo thanhtruong292-spec/NASWebDAV --title "Title" --body "Body"
```

### Close an issue
```bash
gh issue close <number> --repo thanhtruong292-spec/NASWebDAV
```

### Add a comment
```bash
gh issue comment <number> --repo thanhtruong292-spec/NASWebDAV --body "Comment"
```

### Add labels
```bash
gh issue edit <number> --repo thanhtruong292-spec/NASWebDAV --add-label "label-name"
```

## Notes for agent skills

- When `code-review`, `triage`, `to-tickets`, `to-spec`, or `qa` skills refer to "the issue tracker", they mean GitHub Issues on this repo.
- Always use `--repo thanhtruong292-spec/NASWebDAV` to avoid ambiguity.
- Issue numbers are used as canonical identifiers in commit messages (e.g. `Closes #42`).
