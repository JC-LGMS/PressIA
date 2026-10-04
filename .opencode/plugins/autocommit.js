// Auto-commit + push automatique à la fin de chaque itération OpenCode
// (événement « session.idle »). Chargé automatiquement au démarrage :
// aucune entrée dans opencode.json n'est nécessaire.
import { exec } from "node:child_process"
import { promisify } from "node:util"

const run = promisify(exec)

// gh CLI (credential helper de git pour github.com), installé via winget.
// Ajouté au PATH même si opencode a été lancé avant l'installation de gh.
const GH_DIR = "C:\\Program Files\\GitHub CLI"

function gitEnv() {
  const env = { ...process.env }
  const extra = (env.PATH ? env.PATH + ";" : "") + GH_DIR
  env.PATH = extra
  env.Path = extra
  return env
}

export const AutoCommitPlugin = async ({ worktree, client }) => {
  let busy = false
  const git = (cmd) =>
    run(`git ${cmd}`, { cwd: worktree, env: gitEnv(), windowsHide: true, maxBuffer: 10 * 1024 * 1024 })
  return {
    event: async ({ event }) => {
      if (event.type !== "session.idle" || busy) return
      busy = true
      try {
        const { stdout } = await git("status --porcelain")
        if (!stdout.trim()) return
        const stamp = new Date().toISOString().replace("T", " ").slice(0, 19)
        await git("add -A")
        // Email fixé pour les commits OpenCode, indépendamment de la config globale.
        await git(`-c user.email=jclgms@gmail.com commit -m ${JSON.stringify(`Auto-commit ${stamp}`)}`)
        await git("push")
        await client.app.log({
          body: {
            service: "autocommit",
            level: "info",
            message: `Commit + push automatiques (${stamp})`,
            extra: {},
          },
        })
      } catch (err) {
        try {
          await client.app.log({
            body: {
              service: "autocommit",
              level: "warn",
              message: String((err && err.message) || err).slice(0, 300),
              extra: {},
            },
          })
        } catch {}
      } finally {
        busy = false
      }
    },
  }
}
