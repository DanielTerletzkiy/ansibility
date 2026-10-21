waitSmart(); sleep(1000);
// run dialog for a single play
edt(function () { reset(); hideTool("Project"); useEnv("staging"); open("site.yml"); });
sleep(2000);
later(function () { kobject("de.terletzkiy.ansibility.run.PlaybookLauncher").openDialog(project(), vfile("site.yml"), pcls("de.terletzkiy.ansibility.run.PlaybookTarget").getDeclaredConstructor().newInstance()); });
waitDialog(8000); sleep(2500);
edt(function () { clean(); shot(OUT + "/g06-run-dialog.png"); });
edt(closeDialogs); sleep(800); edt(closeDialogs); sleep(500);
settingsPage("Layout", "g07-layout-settings");
settingsPage("Runner", "g08-runner-settings");
settingsPage("Lint", "g09-lint-settings");
