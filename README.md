# DocPocket SMS RDV - Progressive Web App (PWA) 📱💉

Application médicale moderne de prise et gestion de rendez-vous pour cabinets médicaux avec rappels automatiques par **SMS et Email programmés 48 heures avant le rendez-vous**.

---

## 🌟 Fonctionnalités Principales

- **📱 PWA Installable (Progressive Web App)** :
  - S'installe en 1 clic sur smartphone (**Android / iPhone / iPad**), tablette et ordinateur (**Windows / macOS / Linux / ChromeOS**).
  - Mode plein écran immersif (standalone), icône personnalisée sur l'écran d'accueil.
  - Fonctionne **100% hors-connexion** grâce au Service Worker et au cache local.

- **⏱️ Rappels Programmés à 48 heures** :
  - Calcule automatiquement l'envoi du rappel SMS/Email exactement 48h avant l'heure du rendez-vous.
  - Prévisualisation dynamique du SMS en temps réel lors de la saisie (Français, Arabe, Anglais).
  - Envoi direct via WhatsApp / SMS en un clic depuis l'application web.

- **🔄 Synchronisation Hybride Multi-Appareils** :
  - **Mode Autonome (PWA)** : Stockage sécurisé directement dans le navigateur (LocalStorage / IndexedDB).
  - **Mode Connecté (Cabinet)** : Synchronisation directe avec le serveur local du cabinet (API REST Express) et l'application mobile Android **DocPocket SMS RDV** pour l'envoi réel des SMS via carte SIM.

- **📊 Tableau de bord & Gestion des RDV** :
  - Statistiques en direct : RDV du jour, en attente de rappel (48h), rappels envoyés, total.
  - Filtres rapides (Aujourd'hui, À venir, En attente, Envoyés) et recherche instantanée.
  - Sauvegarde et export des données en format **JSON** et **CSV (Excel)**.

---

## 🚀 Utilisation & Installation

### 1. Utilisation Directe sur le Web
Rendez-vous sur l'URL GitHub Pages :  
👉 **[https://bkhmondocteur.github.io/docpocket-sms-rdv/](https://bkhmondocteur.github.io/docpocket-sms-rdv/)**

### 2. Installer comme une Application Native
1. Ouvrez l'application dans Chrome, Edge ou Safari.
2. Cliquez sur le bouton **📲 « Installer l'App »** dans la barre supérieure (ou menu *Partager > Sur l'écran d'accueil* sur iOS).
3. L'application apparaît instantanément sur votre bureau ou écran d'accueil comme une application native.

### 3. Connecter au Serveur du Cabinet (Optionnel)
1. Cliquez sur l'icône **⚙️ Paramètres** en haut à droite.
2. Choisissez le mode **« Serveur Local PC / Wi-Fi »**.
3. Renseignez l'adresse IP de votre PC de cabinet (ex: `http://192.168.1.15:3000`).
4. Vos rendez-vous seront automatiquement partagés entre le PC, la PWA et le téléphone Android !

---

## 📂 Structure du Projet

```text
├── index.html           # Interface utilisateur PWA réactive
├── styles.css           # Thème médical moderne et responsive
├── app.js               # Logique métier, stockage, synchronisation et PWA
├── manifest.json        # Déclaration PWA (W3C Web App Manifest)
├── sw.js                # Service Worker (Cache offline & réseau)
├── favicon.png          # Favicon du site
└── icons/
    ├── icon-192.png     # Icône PWA 192x192
    ├── icon-512.png     # Icône PWA 512x512
    └── apple-touch-icon.png # Icône iOS 180x180
```

---

## 👨‍⚕️ À propos
Développé pour la suite médicale **DocPocket** par Dr. Benkhelil.
Tous droits réservés © 2026.
