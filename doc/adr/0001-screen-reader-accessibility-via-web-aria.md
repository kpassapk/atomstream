# 1. Screen-reader accessibility POC via web ARIA

Date: 2026-06-17

## Status

Proposed

## Scope

This ADR covers the todo-list example as the first target. Generalising the
accessibility model across all mirrored components is deferred.

## Context

atomstream mirrors the Charm/bubbletea component model and renders a UI to two
targets:

- a terminal (TTY) via ANSI escape sequences, and
- a web view (`atomstream.impl.web` → `atomstream.impl.ansi-html`), which
  converts the ANSI string to HTML and serves it as a single
  `<pre id="screen">` element.

We want screen-reader support for the todo-list example: when focus moves, the
newly selected item should be read aloud; when an item is toggled, a descriptive
announcement should be spoken. The constraint is to add this **behind the
scenes**, with minimal or no changes to the Charm DSL that application authors
use.

Key constraints discovered:

- ARIA is an HTML-only mechanism
- The whole UI is one ANSI→HTML `<pre>`. A screen reader reads it as
  one undifferentiated wall of text: no list semantics, no per-item
  state, no announcements on change.
- An ANSI string is flat. It carries no structure on which to hang ARIA
  attributes. 
- The data needed to rebuild that structure already exists in component state:
  the list component tracks `selected-index`; the todo model tracks `:done`; the
  `:todos` vector gives item order, count, and position.

## Decision

Provide screen-reader support **on the web render path only**, by emitting a
semantic, ARIA-annotated HTML region for accessible components instead of
dumping their ANSI into the `<pre>` blob.

### 1. Render target: web only

ARIA lives in HTML. The terminal path is unchanged and keeps emitting ANSI. The
web path gains the accessible structure. No change is required to the Charm DSL.

### 2. Structure: semantic sidecar (not ANSI annotation)

Components expose a small, **optional** accessibility model alongside their ANSI
string — e.g. for the todo list:

```clojure
{:role :listbox
 :label "Todos"
 :active-idx 1
 :items [{:id "todo-0" :name "Learn charm.clj" :checked true}
         {:id "todo-1" :name "Build a TUI app" :checked false}
         {:id "todo-2" :name "Have fun!"       :checked false}]}
```

The web layer builds ARIA HTML from this model. The ANSI string remains the
source of truth for the terminal. This requires **no DSL change** — the data is
already in component state. The rejected alternative (embedding invisible
markers in the ANSI string for `ansi->hiccup` to parse back into roles) is
brittle and hacky.

### 3. ARIA pattern: listbox + `aria-activedescendant` + per-item `aria-checked`

```html
<h1>Todo List</h1>
<p role="status" aria-live="polite">2 pending, 1 done</p>

<div role="listbox" aria-label="Todos" tabindex="0" aria-activedescendant="todo-1">
  <div role="option" id="todo-0" aria-checked="true">Learn charm.clj</div>
  <div role="option" id="todo-1" aria-checked="false">Build a TUI app</div>
  <div role="option" id="todo-2" aria-checked="false">Have fun!</div>
</div>

<div id="announce" class="sr-only" aria-live="polite"></div>
```

- **Navigation (j/k)** updates `aria-activedescendant`. Focus stays parked on the
  listbox container; only one attribute changes. The screen reader auto-announces
  the new option's name, checked state, and position.
- **`aria-activedescendant` is chosen over roving `tabindex`** because every key
  routes through one handler on `#screen` and re-render morphs the whole screen.
  Roving tabindex requires moving real DOM focus on each keystroke, which is lost
  on morph and fragile. activedescendant only mutates an attribute — robust under
  morphdom.

### 4. State is exposed as attributes, never as ASCII

The option's accessible name is the task title only (`Learn charm.clj`). Checked
state lives in `aria-checked`, never read as `[x]`/`[ ]`. Strikethrough stays
purely visual. Counts live in a `role="status"` region that auto-announces when
pending/done change.

### 5. Action feedback: visually-hidden `aria-live="polite"` region

A dedicated `#announce` live region carries action feedback for cases where focus
does **not** move (toggle) or where the verb matters (add/delete):

| Action     | Mechanism                                  | Screen reader says                       |
|------------|--------------------------------------------|------------------------------------------|
| j/k move   | `aria-activedescendant` → option           | "Build a TUI app, not checked, 2 of 3"   |
| x toggle   | write `#announce` + flip `aria-checked`     | "Build a TUI app, checked"               |
| a → add    | write `#announce`                          | "Added Buy milk. 4 todos"                |
| d → delete | write `#announce`                          | "Deleted Build a TUI app. 2 todos"       |

Coordination rule to avoid **double speech** (focus-announce and live region
both firing):

- Navigation → activedescendant announces; live region silent.
- Toggle (focus stationary) → live region announces.
- Add/delete (focus moves) → live region announces the verb + new count.

`polite` for normal feedback; `assertive` reserved for errors.

## Consequences

**Positive**

- Application authors get accessibility for free — no Charm DSL change. The data
  already lives in component state.
- ANSI/terminal output is untouched; the a11y model is a parallel channel for the
  web layer only.
- The listbox + activedescendant pattern is robust under the morphdom re-render
  the web path already uses.

**Negative / costs**

- Adds a second representation (the a11y model) that components must keep in sync
  with their ANSI output.
- Only the web target is accessible; terminal screen-reader users are not served
  by this work.
- The web layer grows a semantic-HTML branch for accessible regions, separate
  from the generic `<pre>` ANSI→HTML path.

## Open questions

- **Source of `#announce` strings.** The toggle/add/delete wording is
  app-level semantics (only the todos app knows "Added X"). Two routes, to be
  decided before implementation:
  1. App emits the announce string as a `Cmd` side-channel — explicit, app
     controls wording.
  2. Framework auto-derives it from a diff of the a11y model — zero app code,
     but generic wording (e.g. just "checked").
- Whether the a11y model is a formal protocol on components or plain map metadata.
- Scope of components beyond `list` (table, text-input, viewport) in later work.
