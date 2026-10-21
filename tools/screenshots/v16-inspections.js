waitSmart(); sleep(1000);
var files = [["roles/nginx/files/dhparam.pem.vault", "!vault", "v16-broken-vault-file"], ["roles/nginx/files/staging.key", "BEGIN", "v17-plaintext-private-key"]];
for (var i = 0; i < files.length; i++) (function (f) {
  edt(function () { reset(); hideTool("Project"); open(f[0]); });
  sleep(4000);
  edt(function () { showTool("Problems View"); editor().getContentComponent().requestFocus(); });
  sleep(2000);
  edt(function () { try { clickLinkLabel("Ignore extension"); } catch (e) {} caretAt(f[1]); editor().getContentComponent().requestFocus(); action("ShowErrorDescription"); });
  sleep(2500);
  edt(function () { clean(); shot(OUT + "/" + f[2] + ".png"); closePopups(); });
})(files[i]);
