(() => {
  const bridge = window.AndroidAppBridge;
  if (!bridge) return;

  let nextRequestId = 0;
  const permissionRequests = new Map();
  const notificationRequests = new Map();
  const hideInstallButton = () => {
    const installButton = document.getElementById("install-app");
    if (installButton) installButton.hidden = true;
  };
  const nativeRegistration = {
    showNotification(title, options = {}) {
      return new Promise((resolve, reject) => {
        const requestId = `notification-${++nextRequestId}`;
        notificationRequests.set(requestId, { resolve, reject });
        bridge.showNotification(
          String(title),
          String(options.body || ""),
          String(options.tag || title),
          requestId
        );
      });
    }
  };

  if (navigator.serviceWorker) {
    Object.defineProperty(navigator.serviceWorker, "ready", {
      configurable: true,
      value: Promise.resolve(nativeRegistration)
    });
  } else {
    Object.defineProperty(navigator, "serviceWorker", {
      configurable: true,
      value: {
        ready: Promise.resolve(nativeRegistration),
        register: () => Promise.resolve(nativeRegistration)
      }
    });
  }

  Object.defineProperty(window, "Notification", {
    configurable: true,
    value: class Notification {
      static get permission() {
        return bridge.permission();
      }

      static requestPermission() {
        return new Promise((resolve) => {
          const requestId = `permission-${++nextRequestId}`;
          permissionRequests.set(requestId, resolve);
          bridge.requestPermission(requestId);
        });
      }
    }
  });

  window.__nativeNotificationPermissionResult = (requestId, permission) => {
    const resolve = permissionRequests.get(requestId);
    if (!resolve) return;
    permissionRequests.delete(requestId);
    resolve(permission);
  };

  window.__nativeNotificationResult = (requestId, error) => {
    const request = notificationRequests.get(requestId);
    if (!request) return;
    notificationRequests.delete(requestId);
    if (error) {
      request.reject(new Error(error));
    } else {
      request.resolve();
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", hideInstallButton, { once: true });
  } else {
    hideInstallButton();
  }
})();
