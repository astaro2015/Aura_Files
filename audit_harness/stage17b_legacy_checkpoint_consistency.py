from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

vault = text('app/src/main/java/com/aurafiles/app/data/AuraVault.kt')
vm = text('app/src/main/java/com/aurafiles/app/ui/FileManagerViewModel.kt')
net = text('app/src/main/java/com/aurafiles/app/network/NetworkProfileRepository.kt')
backend = text('app/src/main/java/com/aurafiles/app/transfer/BackendTransferCore.kt')
ui = text('app/src/main/java/com/aurafiles/app/ui/AuraFileManagerApp.kt')
vault_activity = text('app/src/main/java/com/aurafiles/app/ui/VaultActivity.kt')

# CP04: encryption must verify actual bytes consumed rather than trusting provider size.
require('var totalPlainBytes = 0L' in vault, 'Vault actual-byte counter missing')
require('totalPlainBytes = Math.addExact' in vault, 'Vault byte counter is not overflow-safe')
require('totalPlainBytes != entry.size' in vault, 'Vault source-size reconciliation missing')
require('lifecycleScope.launch' in vault_activity, 'Vault lifecycle-owned jobs missing')

# CP06 / later network audits: cancellation must be distinguished from ordinary failures.
require('catch (cancelled: CancellationException)' in backend, 'Backend cancellation handling missing')
require('throw cancelled' in backend, 'Backend cancellation is not rethrown')

# CP08: NetworkProfile JSON must not persist duplicate TLS keys.
persist_block = net[net.index('private fun persist('):net.index('private fun JSONObject.toProfile')]
require(persist_block.count('.put("tls", profile.tls)') == 1, 'NetworkProfile TLS key duplicated or missing')

# CP09/10: category truncation is visible and reserved internal folders stay hidden.
require('Показано ${cached.items.size} из $expectedCount файлов в категории' in vm,
        'Cached category truncation message missing')
require('Показано ${matching.size} из $expectedCount файлов в категории' in vm,
        'Fresh category truncation message missing')
require('!it.name.equals(".AuraTrash", ignoreCase = true)' in ui, 'AuraTrash filter missing')
require('!AuraVault.isVaultFolder(it.name)' in ui, 'AuraSafe and legacy AuraVault filters missing')

# Lost CP09/10 behavior restored during stage17B: a partial move must state committed progress.
require('В Сейф подтверждённо перемещено ${movedUris.size} из ${requested.size}' in vm,
        'Partial Vault move progress is hidden')
require('if (movedUris.isNotEmpty()) applyExternalDeletions(movedUris)' in vm,
        'Committed Vault moves are not reconciled into UI state')

print('STAGE17B_LEGACY_CHECKPOINT_CONSISTENCY_PASS')
