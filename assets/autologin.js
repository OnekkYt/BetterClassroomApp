function (email, pass) {
  if (window.__bcAuto) return;
  window.__bcAuto = true;
  var KEY = 'bcAutoTries';
  var tries = { n: 0, t: Date.now() };
  try {
    var saved = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    if (saved && Date.now() - saved.t < 120000) tries = saved;
  } catch (e) { /* storage blocked */ }

  function set(el, v) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var shown = function (el) { return el && el.offsetParent !== null; };
  var NEXT = /^(next|continue|dalej|kontynuuj)$/i;

  /* The one button under a sign-in page that already knows who you are. */
  function nextButton() {
    var b = document.querySelector('#identifierNext button') || document.querySelector('#identifierNext');
    if (shown(b)) return b;
    var all = [].slice.call(document.querySelectorAll('button, [role="button"]'));
    for (var i = 0; i < all.length; i++) {
      if (shown(all[i]) && NEXT.test((all[i].textContent || '').trim())) return all[i];
    }
    return null;
  }

  var typedEmail = false, typedPass = false, lastClick = 0, polls = 0;
  function press(el) {
    if (!el || Date.now() - lastClick < 3000) return;
    lastClick = Date.now();
    el.click();
  }

  var timer = setInterval(function () {
    if (++polls > 80) { clearInterval(timer); return; }
    var pw = document.querySelector('input[name="Passwd"]');
    var id = document.querySelector('input#identifierId');

    if (shown(pw)) {
      if (typedPass) return;
      if (tries.n >= 2) { clearInterval(timer); return; }   // wrong password: stop, do not lock the account
      typedPass = true;
      tries = { n: tries.n + 1, t: Date.now() };
      try { sessionStorage.setItem(KEY, JSON.stringify(tries)); } catch (e) { }
      set(pw, pass);
      setTimeout(function () {
        var b = document.querySelector('#passwordNext button') || document.querySelector('#passwordNext');
        if (b) b.click();
      }, 300);
      clearInterval(timer);
      return;
    }

    if (shown(id)) {
      // the address is often already there: then it is only the button
      if (!id.value && email && !typedEmail) { typedEmail = true; set(id, email); }
      if (id.value) press(nextButton());
      return;
    }

    // "Choose an account": the saved one, or the only one there is
    var chips = [].slice.call(document.querySelectorAll('[data-identifier]')).filter(shown);
    var chip = null;
    for (var i = 0; i < chips.length; i++) {
      if (email && chips[i].getAttribute('data-identifier') === email) chip = chips[i];
    }
    if (!chip && !email && chips.length === 1) chip = chips[0];
    if (chip) { press(chip); return; }

    // a page that only shows the account and a button
    press(nextButton());
  }, 500);
}