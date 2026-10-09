# The stack's environment, for any terminal

A plan, not yet built. It spans this repo, vexelray-framework and vexelray-gui. Headless running is out of v1.

## Why

The stack's own tools are built inside its checkouts and called by path. `ottermate` is
`vexelray-gui/vexelray-gui-automation-cli/ottermate`, and nothing puts that folder on a `PATH`. A session
(an AI agent, 2026-10-09) typed `ottermate`, got `command not found`, concluded the tool was missing, and wrote a
socket client from scratch. Every piece it needed existed, and it had used it before; nothing on the way pointed at it.

MainFrame is the stack's terminal and the one stack tool that is installed and on the `PATH` (`installer.json`,
`addToPath`). So it is where the stack's environment is defined, and it hands that environment to any other
terminal as a script to source, the way `~/.cargo/env` does. The released MainFrame is a GUI-subsystem program
whose stdout goes nowhere, so the scripts are generated files, not the output of `mainframe env`.

## What a terminal gets

Two scripts, each for bash (Git Bash included) and PowerShell, written to `~/.mainframe/` (MainFrame's own
settings directory):

| Script | For | What it does |
| --- | --- | --- |
| `env.sh`, `env.ps1` | people | Puts every tool of the manifest on the `PATH`, once however often it is sourced. Defines `vexelray-tools`, which lists the tools and, for one that is not built, the `mvn` command that builds it. |
| `env.ai.sh`, `env.ai.ps1` | AI agents | Everything `env.sh` does, and: every VexelRay application launched from that shell opens its automation socket on a free port (`VEXELRAY_AUTOMATION=0`), and `vexdrive <command...>` launches one with an `ottermate` session as its stdin and stdout (`ottermate --launch <command...>`). |

A profile chooses between them; agents' shells mark themselves (`AI_AGENT`, `CLAUDECODE` in Claude Code's):

```bash
# ~/.bashrc
if [ -n "$AI_AGENT$CLAUDECODE" ]; then . ~/.mainframe/env.ai.sh; else . ~/.mainframe/env.sh; fi
```

The profile line is the user's to add; nothing here edits a profile. MainFrame's own tabs start their shells with
the people's environment, so a tab and a sourced terminal agree.

## The pieces, and whose they are

### 1. vexelray-framework: automation from the environment

`Driver.open` resolves `automation` from `--automation`, then `-Dautomation`, then the environment variable
`VEXELRAY_AUTOMATION`, then off. The order keeps a launch's own flag the most specific. The release editions have no
automation module, so they ignore all three; an agent's environment can never open a socket in a shipped binary.

When the socket binds, the Driver also writes a **session file**, `~/.vexelray/automation/<app>-<pid>.port`, holding
the port, and deletes it at shutdown. A file whose pid is gone is stale, and whoever reads it deletes it. The
directory is the stack's, not MainFrame's, since an application knows nothing of MainFrame.

*Done already:* under the `automation: localhost:<port>` line, the Driver prints a hint naming `ottermate`, its
folder and the command that builds it (vexelray-framework, uncommitted as of this writing).

### 2. ottermate: attach to a session

With no `--port`, `ottermate` reads the session files: one live session is used; several are listed, and `--app
<name>` chooses; none falls back to the default port, as today. So an agent whose shell runs one command at a time
launches an application in the background and drives it with plain `ottermate key R`, never a port number, and
never a client of its own. An agent with a persistent terminal uses `vexdrive`, whose stdin and stdout are the
session.

### 3. MainFrame: the manifest and the generator

- **The manifest**, a resource in this repo: for each tool its command, its folder relative to the stack root, the
  launcher file there, and the command that builds it. `ottermate` is the first entry.
- **The stack root** is a setting, defaulting to `~/Documents/GitHub`.
- **The generator** writes the four scripts at every launch, so they follow the manifest and the settings; a write
  that would change nothing is skipped. Later the installer writes them too, at install.
- **The tabs** start their shells with the people's environment: `ConPty` passes an environment block built from
  MainFrame's own environment plus the manifest's `PATH` entries, rather than the null block that inherits.

### 4. The front door

- vexelray-gui's `docs/guides/ottermate.md` §1 and the applications' READMEs say to source `~/.mainframe/env.sh`
  rather than edit the `PATH` by hand.
- The global `~/.claude/CLAUDE.md` says the stack's tools come from `~/.mainframe/env.ai.sh`.

## Order

1. vexelray-framework: `VEXELRAY_AUTOMATION`, and the session file.
2. ottermate: attaching to a session.
3. MainFrame: the manifest, the generator, the tabs' environment.
4. The guide, the READMEs and the global CLAUDE.md, once the scripts exist; then the user's profile line.

## Not in v1

- **Headless running**: an application whose frame loop presents offscreen, with input only from the socket, so an
  agent's application puts no window on the desktop. Needs vexelray-gui.
- **Tools without the checkouts**: the installer shipping `ottermate` beside MainFrame, so someone who has
  installed MainFrame but cloned nothing still gets the tools. The manifest then names an installed path instead.
