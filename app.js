/**
 * DocPocket SMS RDV - Progressive Web App (PWA)
 * Gestion complète des rendez-vous médicaux avec rappels automatiques à 48h
 */

// --- Default Configuration ---
const DEFAULT_CONFIG = {
  doctorName: "Dr. Benkhelil",
  reminderHours: 48,
  syncMode: "local", // "local" ou "server"
  serverUrl: "http://localhost:3000",
  templates: {
    fr: "Bonjour {Nom du Patient}, ceci est un rappel de votre RDV le {Date} à {Heure} au cabinet du {Nom Du Médecin}. Bonne journée.",
    ar: "مرحباً {Nom du Patient}، هذا تذكير بموعدك يوم {Date} الساعة {Heure} في عيada الدكتور {Nom Du Médecin}. نهارك سعيد.",
    en: "Hello {Nom du Patient}, this is a reminder of your appointment on {Date} at {Heure} at Dr. {Nom Du Médecin}'s clinic. Have a good day."
  }
};

// --- App State ---
let config = loadConfig();
let appointments = loadAppointmentsFromStorage();
let activeFilter = 'all';
let searchQuery = '';
let deferredInstallPrompt = null;

// --- DOM Elements ---
const form = document.getElementById('appointment-form');
const editIdInput = document.getElementById('edit-id');
const patientNameInput = document.getElementById('patient-name');
const patientPhoneInput = document.getElementById('patient-phone');
const patientEmailInput = document.getElementById('patient-email');
const appointmentDateInput = document.getElementById('appointment-date');
const appointmentTimeInput = document.getElementById('appointment-time');
const smsLanguageSelect = document.getElementById('sms-language');
const smsPreviewText = document.getElementById('sms-preview-text');
const smsCharCount = document.getElementById('sms-char-count');
const btnSubmit = document.getElementById('btn-submit');
const btnResetForm = document.getElementById('btn-reset-form');
const searchInput = document.getElementById('search-input');
const tableBody = document.getElementById('appointments-table-body');
const emptyState = document.getElementById('empty-state');
const statusPill = document.getElementById('status-pill');
const statusText = document.getElementById('status-text');
const headerDoctorName = document.getElementById('header-doctor-name');

// Modal Elements
const settingsModal = document.getElementById('settings-modal');
const btnOpenSettings = document.getElementById('btn-open-settings');
const btnCloseSettings = document.getElementById('btn-close-settings');
const btnCancelSettings = document.getElementById('btn-cancel-settings');
const btnSaveSettings = document.getElementById('btn-save-settings');
const syncModeSelect = document.getElementById('sync-mode');
const serverUrlInput = document.getElementById('server-url');
const serverUrlGroup = document.getElementById('server-url-group');
const settingDoctorName = document.getElementById('setting-doctor-name');
const settingReminderHours = document.getElementById('setting-reminder-hours');
const templateFr = document.getElementById('template-fr');
const templateAr = document.getElementById('template-ar');
const templateEn = document.getElementById('template-en');
const btnExportJson = document.getElementById('btn-export-json');
const btnExportCsv = document.getElementById('btn-export-csv');
const fileImportJson = document.getElementById('file-import-json');
const btnSync = document.getElementById('btn-sync');
const btnInstallPwa = document.getElementById('btn-install-pwa');
const installBanner = document.getElementById('install-banner');
const bannerInstallBtn = document.getElementById('banner-install-btn');

// --- Initialization ---
document.addEventListener('DOMContentLoaded', () => {
  initDateInputs();
  applyConfigToUI();
  registerServiceWorker();
  updateSmsPreview();
  renderAppointments();

  // If in server mode, sync on startup
  if (config.syncMode === 'server') {
    syncWithServer(false);
    // Background polling every 10 seconds
    setInterval(() => syncWithServer(false), 10000);
  }
});

// --- PWA Service Worker & Installation ---
function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js')
        .then((reg) => {
          console.log('[PWA] Service Worker enregistré avec succès:', reg.scope);
        })
        .catch((err) => {
          console.warn('[PWA] Échec enregistrement Service Worker:', err);
        });
    });
  }

  // Handle PWA installation prompt
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredInstallPrompt = e;
    if (btnInstallPwa) btnInstallPwa.style.display = 'inline-flex';
    if (installBanner) installBanner.classList.add('active');
  });

  const triggerInstall = async () => {
    if (!deferredInstallPrompt) return;
    deferredInstallPrompt.prompt();
    const { outcome } = await deferredInstallPrompt.userChoice;
    console.log('[PWA] Choix installation utilisateur:', outcome);
    deferredInstallPrompt = null;
    if (btnInstallPwa) btnInstallPwa.style.display = 'none';
    if (installBanner) installBanner.classList.remove('active');
  };

  if (btnInstallPwa) btnInstallPwa.addEventListener('click', triggerInstall);
  if (bannerInstallBtn) bannerInstallBtn.addEventListener('click', triggerInstall);

  window.addEventListener('appinstalled', () => {
    showToast("Application DocPocket installée avec succès ! 🎉", "success");
    if (btnInstallPwa) btnInstallPwa.style.display = 'none';
    if (installBanner) installBanner.classList.remove('active');
  });
}

// --- Date Helpers ---
function initDateInputs() {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  appointmentDateInput.value = `${yyyy}-${mm}-${dd}`;

  // Quick date buttons
  document.querySelectorAll('.quick-date-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const daysToAdd = parseInt(btn.dataset.days, 10);
      const targetDate = new Date();
      targetDate.setDate(targetDate.getDate() + daysToAdd);
      const y = targetDate.getFullYear();
      const m = String(targetDate.getMonth() + 1).padStart(2, '0');
      const d = String(targetDate.getDate()).padStart(2, '0');
      appointmentDateInput.value = `${y}-${m}-${d}`;
      updateSmsPreview();
    });
  });
}

// --- Configuration Management ---
function loadConfig() {
  const saved = localStorage.getItem('docpocket_config');
  if (saved) {
    try {
      return { ...DEFAULT_CONFIG, ...JSON.parse(saved) };
    } catch (e) {
      console.error(e);
    }
  }
  return { ...DEFAULT_CONFIG };
}

function saveConfig(newConfig) {
  config = { ...config, ...newConfig };
  localStorage.setItem('docpocket_config', JSON.stringify(config));
  applyConfigToUI();
}

function applyConfigToUI() {
  if (headerDoctorName) {
    headerDoctorName.innerText = `${config.doctorName || 'Cabinet Médical'} • Rappels ${config.reminderHours}h`;
  }

  // Update Status Pill
  if (config.syncMode === 'server') {
    statusPill.className = 'status-pill local';
    statusText.innerText = `Serveur PC (${config.serverUrl.replace(/^https?:\/\//, '')})`;
  } else {
    statusPill.className = 'status-pill online';
    statusText.innerText = 'Mode Hors-ligne / PWA';
  }

  // Settings form values
  syncModeSelect.value = config.syncMode;
  serverUrlInput.value = config.serverUrl;
  serverUrlGroup.style.display = config.syncMode === 'server' ? 'block' : 'none';
  settingDoctorName.value = config.doctorName;
  settingReminderHours.value = config.reminderHours;
  templateFr.value = config.templates.fr;
  templateAr.value = config.templates.ar;
  templateEn.value = config.templates.en;

  updateSmsPreview();
}

// --- Live SMS Preview ---
function generateSmsMessage(patientName, date, time, lang) {
  const cleanName = patientName.trim() || "[Nom du Patient]";
  const cleanDate = date || "[Date]";
  const cleanTime = time || "[Heure]";
  const doctor = config.doctorName || "le Médecin";

  let template = config.templates[lang] || config.templates.fr;
  return template
    .replace(/{Nom du Patient}/gi, cleanName)
    .replace(/\[Nom\]/gi, cleanName)
    .replace(/{Date}/gi, cleanDate)
    .replace(/\[Date\]/gi, cleanDate)
    .replace(/{Heure}/gi, cleanTime)
    .replace(/\[Heure\]/gi, cleanTime)
    .replace(/{Nom Du Médecin}/gi, doctor);
}

function updateSmsPreview() {
  const name = patientNameInput.value;
  const date = appointmentDateInput.value;
  const time = appointmentTimeInput.value;
  const lang = smsLanguageSelect.value;

  const text = generateSmsMessage(name, date, time, lang);
  smsPreviewText.innerText = text;
  smsPreviewText.style.direction = lang === 'ar' ? 'rtl' : 'ltr';
  smsCharCount.innerText = `${text.length} car.`;
}

// Input listeners for preview
[patientNameInput, appointmentDateInput, appointmentTimeInput, smsLanguageSelect].forEach(el => {
  el.addEventListener('input', updateSmsPreview);
  el.addEventListener('change', updateSmsPreview);
});

// --- Appointment Storage & Persistence ---
function loadAppointmentsFromStorage() {
  const saved = localStorage.getItem('docpocket_appointments');
  if (saved) {
    try {
      return JSON.parse(saved);
    } catch (e) {
      console.error(e);
    }
  }
  return [];
}

function saveAppointmentsToStorage() {
  localStorage.setItem('docpocket_appointments', JSON.stringify(appointments));
  renderAppointments();
}

// --- Sync with PC Express Server ---
async function syncWithServer(showFeedback = true) {
  if (config.syncMode !== 'server') {
    if (showFeedback) showToast("Mode autonome : données sauvegardées en local.", "success");
    return;
  }

  const cleanUrl = config.serverUrl.replace(/\/$/, '');
  const apiUrl = `${cleanUrl}/api/appointments`;

  try {
    const res = await fetch(apiUrl, { method: 'GET', headers: { 'Accept': 'application/json' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    
    const serverMap = await res.json();
    const serverList = [];
    for (const key in serverMap) {
      serverList.push({
        id: key,
        ...serverMap[key]
      });
    }

    // Merge or update local with server list
    appointments = serverList;
    localStorage.setItem('docpocket_appointments', JSON.stringify(appointments));
    renderAppointments();
    
    statusPill.className = 'status-pill online';
    statusText.innerText = `Connecté (${cleanUrl.replace(/^https?:\/\//, '')})`;
    if (showFeedback) showToast("Synchronisation avec le serveur PC réussie ! ✅", "success");
  } catch (err) {
    console.warn("Échec synchro serveur PC:", err.message);
    statusPill.className = 'status-pill';
    statusText.innerText = 'Serveur PC Inaccessible (Mode Cache)';
    if (showFeedback) showToast(`Impossible de joindre le serveur PC (${err.message})`, "error");
  }
}

// --- Form Submission (Create or Update) ---
form.addEventListener('submit', async (e) => {
  e.preventDefault();

  const editId = editIdInput.value;
  const patientName = patientNameInput.value.trim();
  const phone = patientPhoneInput.value.trim();
  const email = patientEmailInput.value.trim();
  const date = appointmentDateInput.value;
  const time = appointmentTimeInput.value;
  const lang = smsLanguageSelect.value;

  if (!patientName || !phone || !date || !time) {
    showToast("Veuillez renseigner tous les champs obligatoires (*)", "error");
    return;
  }

  const generatedSms = generateSmsMessage(patientName, date, time, lang);

  btnSubmit.disabled = true;
  btnSubmit.innerText = editId ? "Mise à jour..." : "Enregistrement...";

  const appointmentData = {
    nomPatient: patientName,
    telephone: phone,
    email: email || null,
    dateRdv: date,
    heureRdv: time,
    texteSms: generatedSms,
    language: lang,
    smsEnvoye: false,
    emailEnvoye: false,
    synced: false,
    timestamp: Date.now()
  };

  if (config.syncMode === 'server') {
    // Send to PC server
    const cleanUrl = config.serverUrl.replace(/\/$/, '');
    try {
      if (editId) {
        // PUT update
        const res = await fetch(`${cleanUrl}/api/appointments/${editId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(appointmentData)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
      } else {
        // POST create
        const res = await fetch(`${cleanUrl}/api/appointments`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(appointmentData)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
      }
      await syncWithServer(false);
      showToast(editId ? "Rendez-vous mis à jour avec le serveur !" : "Rendez-vous synchronisé avec le serveur PC !", "success");
    } catch (err) {
      console.warn("Erreur serveur, sauvegarde locale de secours:", err);
      saveLocally(editId, appointmentData);
      showToast("Serveur PC indisponible : enregistré localement.", "error");
    }
  } else {
    // Pure Local / PWA mode
    saveLocally(editId, appointmentData);
    showToast(editId ? "Rendez-vous modifié avec succès !" : "Rendez-vous enregistré ! Rappel programmé à 48h.", "success");
  }

  resetForm();
  btnSubmit.disabled = false;
});

function saveLocally(editId, data) {
  if (editId) {
    const idx = appointments.findIndex(a => a.id == editId);
    if (idx !== -1) {
      appointments[idx] = { ...appointments[idx], ...data, id: editId };
    }
  } else {
    const newId = 'local_' + Date.now().toString(36) + Math.random().toString(36).substr(2, 4);
    appointments.unshift({ id: newId, ...data });
  }
  saveAppointmentsToStorage();
}

// --- Reset Form ---
function resetForm() {
  form.reset();
  editIdInput.value = "";
  initDateInputs();
  document.getElementById('form-title').innerHTML = `<span>📝</span> Nouveau Rendez-Vous`;
  btnSubmit.innerText = "💾 Enregistrer & Programmer (48h)";
  updateSmsPreview();
}
btnResetForm.addEventListener('click', resetForm);

// --- Render Table & Dashboard Stats ---
function renderAppointments() {
  tableBody.innerHTML = '';

  const now = new Date();
  const todayStr = getFormattedDate(now);

  // Stats Counters
  let countToday = 0;
  let countPending = 0;
  let countSent = 0;
  let countTotal = appointments.length;

  appointments.forEach(app => {
    if (app.dateRdv === todayStr) countToday++;
    if (app.smsEnvoye || app.emailEnvoye) countSent++;
    else countPending++;
  });

  document.getElementById('stat-today').innerText = countToday;
  document.getElementById('stat-pending').innerText = countPending;
  document.getElementById('stat-sent').innerText = countSent;
  document.getElementById('stat-total').innerText = countTotal;

  // Tab counters
  document.getElementById('count-all').innerText = countTotal;
  document.getElementById('count-today').innerText = countToday;
  document.getElementById('count-pending').innerText = countPending;
  document.getElementById('count-sent').innerText = countSent;

  // Filter & Search
  let filtered = [...appointments];

  if (activeFilter === 'today') {
    filtered = filtered.filter(a => a.dateRdv === todayStr);
  } else if (activeFilter === 'upcoming') {
    filtered = filtered.filter(a => a.dateRdv >= todayStr);
  } else if (activeFilter === 'pending') {
    filtered = filtered.filter(a => !a.smsEnvoye && !a.emailEnvoye);
  } else if (activeFilter === 'sent') {
    filtered = filtered.filter(a => a.smsEnvoye || a.emailEnvoye);
  }
  document.getElementById('count-upcoming').innerText = appointments.filter(a => a.dateRdv >= todayStr).length;

  if (searchQuery.trim()) {
    const q = searchQuery.toLowerCase().trim();
    filtered = filtered.filter(a => 
      (a.nomPatient && a.nomPatient.toLowerCase().includes(q)) ||
      (a.telephone && a.telephone.toLowerCase().includes(q)) ||
      (a.dateRdv && a.dateRdv.includes(q)) ||
      (a.email && a.email.toLowerCase().includes(q))
    );
  }

  // Sort by appointment date & time ascending
  filtered.sort((a, b) => {
    const dtA = `${a.dateRdv || ''} ${a.heureRdv || ''}`;
    const dtB = `${b.dateRdv || ''} ${b.heureRdv || ''}`;
    return dtA.localeCompare(dtB);
  });

  if (filtered.length === 0) {
    emptyState.style.display = 'block';
    return;
  }
  emptyState.style.display = 'none';

  filtered.forEach(app => {
    const tr = document.createElement('tr');

    // Proximity calculation
    const rdvDate = new Date(`${app.dateRdv}T${app.heureRdv || '00:00'}`);
    const diffHours = Math.round((rdvDate.getTime() - now.getTime()) / (1000 * 60 * 60));

    let delayBadge = '';
    if (diffHours < 0) {
      delayBadge = '<span class="badge" style="background:#f1f5f9; color:#64748b;">Passé</span>';
    } else if (diffHours <= 24) {
      delayBadge = '<span class="badge badge-proximity">Dans 24h</span>';
    } else if (diffHours <= 48) {
      delayBadge = '<span class="badge" style="background:#fef3c7; color:#92400e;">Dans 48h (Rappel)</span>';
    } else {
      const days = Math.round(diffHours / 24);
      delayBadge = `<span class="badge" style="background:#f0fdf4; color:#166534;">Dans ${days} jours</span>`;
    }

    // Status Badge
    let statusBadge = '';
    if (app.smsEnvoye || app.emailEnvoye) {
      const badges = [];
      if (app.smsEnvoye) badges.push('SMS envoyé');
      if (app.emailEnvoye) badges.push('Mail envoyé');
      statusBadge = `<span class="badge badge-sent">✓ ${badges.join(' & ')}</span>`;
    } else {
      statusBadge = `<span class="badge badge-pending">⏳ En attente (48h)</span>`;
    }

    // Contact Display with links
    const cleanPhone = (app.telephone || '').replace(/[\s-]/g, '');
    const phoneHtml = cleanPhone 
      ? `<a href="tel:${cleanPhone}">📞 ${app.telephone}</a>` 
      : '-';
    const emailHtml = app.email 
      ? `<br><a href="mailto:${app.email}" style="font-size:0.75rem; color:var(--text-muted);">✉️ ${app.email}</a>` 
      : '';

    tr.innerHTML = `
      <td>
        ${cleanPhone ? `<button class="btn btn-sm btn-primary" title="Envoyer le SMS immédiatement" style="padding: 4px 8px; font-size: 0.75rem; white-space: nowrap;" onclick="sendSmsNow('${escapeHtml(app.telephone)}', '${escapeHtml(app.texteSms || '')}', '${app.id}')">🚀 SMS</button>` : '-'}
      </td>
      <td>
        <div class="patient-info">${escapeHtml(app.nomPatient || '-')}</div>
        <small style="color:var(--text-muted); font-size:0.75rem;">Langue: ${app.language ? app.language.toUpperCase() : 'FR'}</small>
      </td>
      <td class="contact-cell">
        ${phoneHtml}
        ${emailHtml}
      </td>
      <td class="datetime-cell">
        <strong>${formatDateFrench(app.dateRdv)}</strong>
        <div class="time-badge">🕒 ${app.heureRdv || '-'}</div>
      </td>
      <td>${delayBadge}</td>
      <td>${statusBadge}</td>
      <td style="text-align: right;">
        <div class="action-buttons" style="justify-content: flex-end;">
          ${cleanPhone ? `<button class="btn-action" title="Envoyer WhatsApp" onclick="sendWhatsApp('${escapeHtml(app.telephone)}', '${escapeHtml(app.texteSms || '')}')">💬</button>` : ''}
          <button class="btn-action" title="Basculer Statut Envoyé" onclick="toggleSentStatus('${app.id}')">
            ${app.smsEnvoye ? '↩️' : '✅'}
          </button>
          <button class="btn-action" title="Modifier" onclick="startEditAppointment('${app.id}')">✏️</button>
          <button class="btn-action delete" title="Supprimer" onclick="deleteAppointment('${app.id}')">🗑️</button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
}

// --- Action Handlers ---
window.startEditAppointment = function(id) {
  const app = appointments.find(a => a.id == id);
  if (!app) return;

  editIdInput.value = app.id;
  patientNameInput.value = app.nomPatient || '';
  patientPhoneInput.value = app.telephone || '';
  patientEmailInput.value = app.email || '';
  appointmentDateInput.value = app.dateRdv || '';
  appointmentTimeInput.value = app.heureRdv || '09:00';
  smsLanguageSelect.value = app.language || 'fr';

  document.getElementById('form-title').innerHTML = `<span>✏️</span> Modifier le Rendez-Vous`;
  btnSubmit.innerText = "💾 Enregistrer les Modifications";
  updateSmsPreview();

  window.scrollTo({ top: 0, behavior: 'smooth' });
};

window.deleteAppointment = async function(id) {
  if (!confirm("Voulez-vous vraiment supprimer ce rendez-vous ?")) return;

  if (config.syncMode === 'server') {
    const cleanUrl = config.serverUrl.replace(/\/$/, '');
    try {
      await fetch(`${cleanUrl}/api/appointments/${id}`, { method: 'DELETE' });
      await syncWithServer(false);
    } catch (e) {
      console.warn("Échec suppression serveur:", e);
    }
  }

  appointments = appointments.filter(a => a.id != id);
  saveAppointmentsToStorage();
  showToast("Rendez-vous supprimé.", "success");
};

window.toggleSentStatus = async function(id) {
  const app = appointments.find(a => a.id == id);
  if (!app) return;

  const newStatus = !app.smsEnvoye;
  app.smsEnvoye = newStatus;

  if (config.syncMode === 'server') {
    const cleanUrl = config.serverUrl.replace(/\/$/, '');
    try {
      await fetch(`${cleanUrl}/api/appointments/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ smsEnvoye: newStatus })
      });
    } catch (e) {}
  }

  saveAppointmentsToStorage();
  showToast(newStatus ? "Marqué comme SMS Envoyé !" : "Remis en attente.", "success");
};

window.sendWhatsApp = function(phone, message) {
  const clean = phone.replace(/[\s-+()]/g, '');
  const url = `https://wa.me/${clean}?text=${encodeURIComponent(message)}`;
  window.open(url, '_blank');
};

window.sendSmsNow = function(phone, message, id) {
  if (!phone) {
    showToast("Numéro de téléphone manquant", "error");
    return;
  }
  const clean = phone.replace(/[\s-]/g, '');
  const url = `sms:${clean}?body=${encodeURIComponent(message || '')}`;
  window.open(url, '_blank');
  if (id) {
    const app = appointments.find(a => a.id == id);
    if (app && !app.smsEnvoye) {
      window.toggleSentStatus(id);
    }
  }
  showToast("Ouverture de l'application SMS...", "success");
};

// Filter tabs
document.querySelectorAll('.filter-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    activeFilter = tab.dataset.filter;
    renderAppointments();
  });
});

// Search input
searchInput.addEventListener('input', (e) => {
  searchQuery = e.target.value;
  renderAppointments();
});

// Manual Sync Button
btnSync.addEventListener('click', () => {
  syncWithServer(true);
});

// Status pill click opens settings
statusPill.addEventListener('click', () => {
  openSettingsModal();
});

// --- Settings Modal ---
function openSettingsModal() {
  settingsModal.classList.add('open');
}

function closeSettingsModal() {
  settingsModal.classList.remove('open');
}

btnOpenSettings.addEventListener('click', openSettingsModal);
btnCloseSettings.addEventListener('click', closeSettingsModal);
btnCancelSettings.addEventListener('click', closeSettingsModal);

syncModeSelect.addEventListener('change', () => {
  serverUrlGroup.style.display = syncModeSelect.value === 'server' ? 'block' : 'none';
});

btnSaveSettings.addEventListener('click', () => {
  const newConfig = {
    syncMode: syncModeSelect.value,
    serverUrl: serverUrlInput.value.trim() || "http://localhost:3000",
    doctorName: settingDoctorName.value.trim(),
    reminderHours: parseInt(settingReminderHours.value, 10) || 48,
    templates: {
      fr: templateFr.value.trim(),
      ar: templateAr.value.trim(),
      en: templateEn.value.trim()
    }
  };

  saveConfig(newConfig);
  closeSettingsModal();
  showToast("Paramètres sauvegardés !", "success");

  if (newConfig.syncMode === 'server') {
    syncWithServer(true);
  }
});

// --- Export & Import ---
btnExportJson.addEventListener('click', () => {
  const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(appointments, null, 2));
  const a = document.createElement('a');
  a.setAttribute("href", dataStr);
  a.setAttribute("download", `docpocket_rdv_${getFormattedDate(new Date())}.json`);
  document.body.appendChild(a);
  a.click();
  a.remove();
  showToast("Export JSON téléchargé !", "success");
});

btnExportCsv.addEventListener('click', () => {
  let csv = "ID,Patient,Telephone,Email,Date,Heure,StatutSMS,Langue\n";
  appointments.forEach(a => {
    csv += `"${a.id || ''}","${(a.nomPatient || '').replace(/"/g, '""')}","${a.telephone || ''}","${a.email || ''}","${a.dateRdv || ''}","${a.heureRdv || ''}","${a.smsEnvoye ? 'Envoyé' : 'En attente'}","${a.language || 'fr'}"\n`;
  });
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `docpocket_rdv_${getFormattedDate(new Date())}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  showToast("Export CSV téléchargé !", "success");
});

fileImportJson.addEventListener('change', (e) => {
  const file = e.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = (event) => {
    try {
      const imported = JSON.parse(event.target.result);
      if (Array.isArray(imported)) {
        appointments = imported;
        saveAppointmentsToStorage();
        showToast(`${imported.length} rendez-vous importés avec succès !`, "success");
      } else {
        showToast("Format de fichier JSON invalide.", "error");
      }
    } catch (err) {
      showToast("Erreur lors de la lecture du fichier JSON.", "error");
    }
  };
  reader.readAsText(file);
});

// --- Utilities ---
function getFormattedDate(d) {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function formatDateFrench(dateStr) {
  if (!dateStr) return '-';
  const parts = dateStr.split('-');
  if (parts.length === 3) {
    return `${parts[2]}/${parts[1]}/${parts[0]}`;
  }
  return dateStr;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.innerText = text;
  return div.innerHTML;
}

function showToast(message, type = "") {
  const toast = document.getElementById('toast');
  toast.innerText = message;
  toast.className = 'show ' + type;
  setTimeout(() => {
    toast.className = '';
  }, 3500);
}
