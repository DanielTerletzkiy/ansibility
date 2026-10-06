document.addEventListener('DOMContentLoaded', () => {
  // 1. Tab Switching for Showcase Box
  const tabs = document.querySelectorAll('.preview-tab');
  const tabPanels = document.querySelectorAll('.tab-panel');

  const positionTwoslashPopup = (hover) => {
    const popup = hover.querySelector(':scope > .twoslash-popup');
    if (!popup) return;

    popup.classList.add('is-floating');
    popup.style.left = '0px';
    popup.style.top = '0px';

    const anchor = hover.getBoundingClientRect();
    const bounds = popup.getBoundingClientRect();
    const margin = 8;
    const left = Math.min(Math.max(margin, anchor.left), window.innerWidth - bounds.width - margin);
    const aboveTop = anchor.top - bounds.height - margin;
    const opensBelow = aboveTop < margin;
    const top = opensBelow
      ? Math.min(anchor.bottom + margin, window.innerHeight - bounds.height - margin)
      : aboveTop;

    popup.classList.toggle('opens-below', opensBelow);
    popup.style.left = `${left}px`;
    popup.style.top = `${Math.max(margin, top)}px`;
    popup.style.setProperty('--popup-arrow-left', `${Math.max(margin, Math.min(anchor.left + anchor.width / 2 - left, bounds.width - margin))}px`);
  };

  const clearTwoslashPopupPosition = (hover) => {
    const popup = hover.querySelector(':scope > .twoslash-popup');
    if (!popup) return;

    popup.classList.remove('is-floating', 'opens-below');
    popup.style.removeProperty('left');
    popup.style.removeProperty('top');
    popup.style.removeProperty('--popup-arrow-left');
  };

  document.querySelectorAll('.twoslash-hover').forEach((hover) => {
    hover.addEventListener('mouseenter', () => positionTwoslashPopup(hover));
    hover.addEventListener('mouseleave', () => clearTwoslashPopupPosition(hover));
    hover.addEventListener('focusin', () => positionTwoslashPopup(hover));
    hover.addEventListener('focusout', () => {
      if (!hover.contains(document.activeElement)) clearTwoslashPopupPosition(hover);
    });
  });

  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const targetTab = tab.getAttribute('data-tab');
      tabs.forEach((t) => t.classList.remove('active'));
      tabPanels.forEach((p) => p.classList.remove('active'));

      tab.classList.add('active');
      const targetPanel = document.getElementById(`tab-${targetTab}`);
      if (targetPanel) {
        targetPanel.classList.add('active');
      }
    });
  });

  // 2. Precedence Host Context Switching
  const precHostSelect = document.getElementById('prec-host-select');
  const precVarVal = document.getElementById('prec-var-val');
  const precVarComment = document.getElementById('prec-var-comment');
  const precWorkersVal = document.getElementById('prec-workers-val');
  const precWorkersComment = document.getElementById('prec-workers-comment');
  const precBreakdownList = document.getElementById('prec-breakdown-list');
  const precQueryText = document.getElementById('prec-query-text');
  const precQuerySource = document.getElementById('prec-query-source');
  const precPopupSource = document.getElementById('prec-popup-source');
  const precPopupVal = document.getElementById('prec-popup-val');
  const precPopupHost = document.getElementById('prec-popup-host');
  const precWorkersSource = document.getElementById('prec-workers-source');

  const precData = {
    'web-prod-01': {
      port: '443',
      portComment: '# Resolved: group_vars/production.yml',
      workers: '8',
      workersComment: '# Resolved: host_vars/web-prod-01.yml',
      source: 'group_vars/production.yml',
      workersSource: 'host_vars/web-prod-01.yml',
      queryText: '(variable) listen_port: int = 443',
      querySource: '(winner: group_vars/production.yml • Layer 08)',
      breakdown: `
        <div class="prec-row winner">
          <span class="prec-level">08</span>
          <div class="prec-details">
            <div class="prec-source">inventory group_vars/production.yml</div>
            <div class="prec-val">listen_port: 443 <span class="badge badge-winner">Active Winner</span></div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">07</span>
          <div class="prec-details">
            <div class="prec-source">inventory group_vars/all.yml</div>
            <div class="prec-val">listen_port: 80 (Overridden)</div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">04</span>
          <div class="prec-details">
            <div class="prec-source">roles/nginx_service/defaults/main.yml</div>
            <div class="prec-val">listen_port: 80 (Overridden)</div>
          </div>
        </div>
      `
    },
    'web-stage-01': {
      port: '8080',
      portComment: '# Resolved: group_vars/staging.yml',
      workers: '2',
      workersComment: '# Resolved: host_vars/web-stage-01.yml',
      source: 'group_vars/staging.yml',
      workersSource: 'host_vars/web-stage-01.yml',
      queryText: '(variable) listen_port: int = 8080',
      querySource: '(winner: group_vars/staging.yml • Layer 08)',
      breakdown: `
        <div class="prec-row winner">
          <span class="prec-level">08</span>
          <div class="prec-details">
            <div class="prec-source">inventory group_vars/staging.yml</div>
            <div class="prec-val">listen_port: 8080 <span class="badge badge-winner">Active Winner</span></div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">07</span>
          <div class="prec-details">
            <div class="prec-source">inventory group_vars/all.yml</div>
            <div class="prec-val">listen_port: 80 (Overridden)</div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">04</span>
          <div class="prec-details">
            <div class="prec-source">roles/nginx_service/defaults/main.yml</div>
            <div class="prec-val">listen_port: 80 (Overridden)</div>
          </div>
        </div>
      `
    },
    'db-prod-01': {
      port: '5432',
      portComment: '# Resolved: group_vars/databases.yml',
      workers: '16',
      workersComment: '# Resolved: host_vars/db-prod-01.yml',
      source: 'group_vars/databases.yml',
      workersSource: 'host_vars/db-prod-01.yml',
      queryText: '(variable) listen_port: int = 5432',
      querySource: '(winner: inventory host_vars/db-prod-01.yml • Layer 12)',
      breakdown: `
        <div class="prec-row winner">
          <span class="prec-level">12</span>
          <div class="prec-details">
            <div class="prec-source">inventory host_vars/db-prod-01.yml</div>
            <div class="prec-val">listen_port: 5432 <span class="badge badge-winner">Active Winner</span></div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">08</span>
          <div class="prec-details">
            <div class="prec-source">inventory group_vars/databases.yml</div>
            <div class="prec-val">listen_port: 5432 (Overridden)</div>
          </div>
        </div>
        <div class="prec-row">
          <span class="prec-level">04</span>
          <div class="prec-details">
            <div class="prec-source">roles/nginx_service/defaults/main.yml</div>
            <div class="prec-val">listen_port: 80 (Overridden)</div>
          </div>
        </div>
      `
    }
  };

  if (precHostSelect) {
    precHostSelect.addEventListener('change', (e) => {
      const selectedHost = e.target.value;
      const selected = precData[selectedHost] || precData['web-prod-01'];
      if (precVarVal) precVarVal.textContent = selected.port;
      if (precVarComment) precVarComment.textContent = selected.portComment;
      if (precWorkersVal) precWorkersVal.textContent = selected.workers;
      if (precWorkersComment) precWorkersComment.textContent = selected.workersComment;
      if (precBreakdownList) precBreakdownList.innerHTML = selected.breakdown;
      if (precQueryText) precQueryText.textContent = selected.queryText;
      if (precQuerySource) precQuerySource.textContent = selected.querySource;
      if (precPopupSource) precPopupSource.textContent = selected.source;
      if (precPopupVal) precPopupVal.textContent = selected.port;
      if (precPopupHost) precPopupHost.textContent = selectedHost;
      if (precWorkersSource) precWorkersSource.textContent = selected.workersSource;
    });
  }

  // 3. Vault Interactive Demo
  const vaultLockBtn = document.getElementById('vault-lock-btn');
  const vaultToggleReveal = document.getElementById('vault-toggle-reveal');
  const vaultPopup = document.getElementById('vault-popup');
  const popupCloseBtn = document.getElementById('popup-close-btn');
  const popupTimer = document.getElementById('popup-timer');
  const copySecretBtn = document.getElementById('copy-secret-btn');
  const vaultToggleLint = document.getElementById('vault-toggle-lint');
  const vaultLintBanner = document.getElementById('vault-lint-banner');
  const vaultHeaderText = document.getElementById('vault-header-text');

  let timerInterval = null;

  function showVaultPopup() {
    if (!vaultPopup) return;
    vaultPopup.style.display = 'block';
    let timeLeft = 10;
    if (popupTimer) popupTimer.textContent = `${timeLeft}s auto-mask`;

    if (timerInterval) clearInterval(timerInterval);
    timerInterval = setInterval(() => {
      timeLeft -= 1;
      if (timeLeft <= 0) {
        clearInterval(timerInterval);
        vaultPopup.style.display = 'none';
      } else if (popupTimer) {
        popupTimer.textContent = `${timeLeft}s auto-mask`;
      }
    }, 1000);
  }

  function hideVaultPopup() {
    if (vaultPopup) vaultPopup.style.display = 'none';
    if (timerInterval) clearInterval(timerInterval);
  }

  if (vaultLockBtn) vaultLockBtn.addEventListener('click', showVaultPopup);
  if (vaultToggleReveal) vaultToggleReveal.addEventListener('click', showVaultPopup);
  if (popupCloseBtn) popupCloseBtn.addEventListener('click', hideVaultPopup);

  if (copySecretBtn) {
    copySecretBtn.addEventListener('click', () => {
      navigator.clipboard.writeText('P@ssw0rd_Sup3r_S3cur3!').then(() => {
        copySecretBtn.textContent = 'Copied!';
        setTimeout(() => {
          copySecretBtn.textContent = 'Copy Secret';
        }, 1500);
      });
    });
  }

  let isLintActive = false;
  if (vaultToggleLint) {
    vaultToggleLint.addEventListener('click', () => {
      isLintActive = !isLintActive;
      if (vaultLintBanner) {
        vaultLintBanner.style.display = isLintActive ? 'flex' : 'none';
      }
      if (vaultHeaderText) {
        vaultHeaderText.textContent = isLintActive
          ? '$ANSIBLE_VAULT;1.2;UNKNOWN'
          : '$ANSIBLE_VAULT;1.2;AES256;prod_vault';
      }
    });
  }

  // 4. Role Argument Specs Diagnostics Toggle
  const specsToggleMode = document.getElementById('specs-toggle-mode');
  const specPortVal = document.getElementById('spec-port-val');
  const specEngineVal = document.getElementById('spec-engine-val');
  const specDiagBox = document.getElementById('spec-diag-box');
  const specStatusMsg = document.getElementById('spec-status-msg');
  const specQueryPin = document.getElementById('spec-query-pin');
  const specErrorCallout = document.getElementById('spec-error-callout');

  let isSpecError = false;
  if (specsToggleMode) {
    specsToggleMode.addEventListener('click', () => {
      isSpecError = !isSpecError;
      if (isSpecError) {
        if (specPortVal) {
          specPortVal.outerHTML = '<span class="syn-s twoslash-error" id="spec-port-val">"5432"</span> <span class="twoslash-error-badge" title="ANS-T001: Expected int, received string">ANS-T001</span>';
        }
        const updatedEngine = document.getElementById('spec-engine-val');
        if (updatedEngine) {
          updatedEngine.outerHTML = '<span class="syn-s twoslash-error" id="spec-engine-val">"oracle-db"</span>';
        }
        if (specDiagBox) specDiagBox.style.display = 'block';
        if (specQueryPin) specQueryPin.style.display = 'none';
        if (specErrorCallout) specErrorCallout.style.display = 'flex';
        if (specStatusMsg) {
          specStatusMsg.innerHTML = '<span class="badge" style="background-color: rgba(244, 63, 94, 0.2); border: 1px solid var(--accent-rose); color: #fda4af;">⚠ 2 diagnostic violations detected (ANS-T001, ANS-T004)</span>';
        }
      } else {
        const portElem = document.getElementById('spec-port-val');
        if (portElem && portElem.parentElement) {
          const parent = portElem.parentElement;
          parent.innerHTML = '<span class="twoslash-hover"><span class="syn-p">    port</span><span class="twoslash-popup"><span class="twoslash-popup-header"><span class="twoslash-popup-type">(argument) port: int</span><span class="twoslash-popup-tag">Optional &bull; default: 5432</span></span><span class="twoslash-popup-doc">Port on which the database instance listens for client queries.</span></span></span>: <span class="syn-n" id="spec-port-val">5432</span>';
        }
        const engineElem = document.getElementById('spec-engine-val');
        if (engineElem) {
          engineElem.outerHTML = '<span class="syn-s" id="spec-engine-val">"postgres"</span>';
        }
        if (specDiagBox) specDiagBox.style.display = 'none';
        if (specQueryPin) specQueryPin.style.display = 'flex';
        if (specErrorCallout) specErrorCallout.style.display = 'none';
        if (specStatusMsg) {
          specStatusMsg.innerHTML = '<span class="badge badge-winner">✓ All 3 parameters strictly match argument_specs.yml</span>';
        }
      }
    });
  }

  // 5. Jinja2 Split Preview
  const jinjaHostSelect = document.getElementById('jinja-host-select');
  const jinjaTargetLabel = document.getElementById('jinja-target-label');
  const jinjaResWorkers = document.getElementById('jinja-res-workers');
  const jinjaResConns = document.getElementById('jinja-res-conns');
  const jinjaResPort = document.getElementById('jinja-res-port');
  const jinjaResHost = document.getElementById('jinja-res-host');
  const jinjaPopupCores = document.getElementById('jinja-popup-cores');

  const jinjaData = {
    'web-prod-01': {
      target: 'web-prod-01',
      workers: '8',
      conns: '8192',
      port: '443',
      host: 'web-prod-01',
      cores: '8'
    },
    'web-stage-01': {
      target: 'web-stage-01',
      workers: '2',
      conns: '2048',
      port: '8080',
      host: 'web-stage-01',
      cores: '2'
    }
  };

  if (jinjaHostSelect) {
    jinjaHostSelect.addEventListener('change', (e) => {
      const data = jinjaData[e.target.value] || jinjaData['web-prod-01'];
      if (jinjaTargetLabel) jinjaTargetLabel.textContent = data.target;
      if (jinjaResWorkers) jinjaResWorkers.textContent = data.workers;
      if (jinjaResConns) jinjaResConns.textContent = data.conns;
      if (jinjaResPort) jinjaResPort.textContent = data.port;
      if (jinjaResHost) jinjaResHost.textContent = data.host;
      if (jinjaPopupCores) jinjaPopupCores.textContent = data.cores;
    });
  }

  // 6. Generic Code Copy Button
  document.querySelectorAll('.code-copy-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const targetId = btn.getAttribute('data-target');
      const targetElem = targetId ? document.getElementById(targetId) : null;
      if (!targetElem) return;

      const text = targetElem.innerText || targetElem.textContent;
      navigator.clipboard.writeText(text.trim()).then(() => {
        const originalText = btn.textContent;
        btn.textContent = 'Copied!';
        btn.style.color = '#38bdf8';
        setTimeout(() => {
          btn.textContent = originalText;
          btn.style.color = '';
        }, 2000);
      }).catch((err) => {
        console.error('Failed to copy text', err);
      });
    });
  });
});
