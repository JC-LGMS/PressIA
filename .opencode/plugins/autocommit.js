// Auto-commit + push automatique à la fin de chaque itération OpenCode (« session.idle »).
// Chargé automatiquement au démarrage : aucune entrée dans opencode.json n'est nécessaire.
//
// Porté sur l'API des plugins OpenCode 2.x — export par défaut `{ id, setup }`, écoute des
// événements par `ctx.event.subscribe()`. L'ancienne forme (fonction exportée) n'est plus
// reconnue : le serveur la rejetait à chaque démarrage (« failed to load plugin »).
//
// DÉSACTIVÉ : tant que `ENABLED` vaut false, le plugin se charge sans rien faire — aucun
// `git add`, aucun commit, aucun push. Passer la constante à true pour le réactiver.
import { execFile } from "node:child_process"
import { promisify } from "node:util"

export const ENABLED = false

const run = promisify(execFile)

// gh CLI (credential helper de git pour github.com), installé via winget.
// Ajouté au PATH même si OpenCode a été lancé avant l'installation de gh.
const GH_DIR = "C:\\Program Files\\GitHub CLI"

function gitEnv() {
  const env = { ...process.env }
  const extra = (env.PATH ? env.PATH + ";" : "") + GH_DIR
  env.PATH = extra
  env.Path = extra
  return env
}

/** Commit + push du répertoire de travail, si quelque chose a changé. */
async function autoCommit(git) {
  const { stdout } = await git("status", "--porcelain")
  if (!stdout.trim()) return null
  const stamp = new Date().toISOString().replace("T", " ").slice(0, 19)
  await git("add", "-A")
  // Email fixé pour les commits OpenCode, indépendamment de la config globale.
  await git("-c", "user.email=jclgms@gmail.com", "commit", "-m", `Auto-commit ${stamp}`)
  await git("push")
  return stamp
}

export default {
  id: "autocommit",
  async setup(ctx) {
    if (!ENABLED) return

    const cwd = ctx.location.directory
    const git = (...args) =>
      run("git", args, {
        cwd,
        env: gitEnv(),
        windowsHide: true,
        maxBuffer: 10 * 1024 * 1024,
      })

    let busy = false
    const events = ctx.event.subscribe()[Symbol.asyncIterator]()
    const pump = (async () => {
      for (;;) {
        const next = await events.next()
        if (next.done) return
        if (next.value?.type !== "session.idle" || busy) continue
        busy = true
        try {
          const stamp = await autoCommit(git)
          if (stamp) console.log(`[autocommit] commit + push (${stamp})`)
        } catch (err) {
          console.warn(`[autocommit] ${String((err && err.message) || err).slice(0, 300)}`)
        } finally {
          busy = false
        }
      }
    })().catch((err) => console.warn(`[autocommit] flux interrompu : ${err}`))

    return async () => {
      await events.return?.()
      await pump
    }
  },
}
