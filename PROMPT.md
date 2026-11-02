# Step 1: Read-only recon (paste the block below into the coding assistant on the Mac)

```
Reply to me in Chinese.

First read AGENTS.md in this repository and strictly follow its safety rules.

This task is read-only recon only; do not change anything on the head unit:
1. Check whether adb is installed; if not, tell me to run brew install android-platform-tools.
2. Help me connect to the head unit (I will give you the IP, or I have already connected via USB).
3. Run tools/recon.sh to collect information.
4. Read the output and screenshots under recon/ and put together a report for me:
   - Screen resolution and density, and the approximate position and size of the Android area
   - Which package and which Activity is the current default launcher
   - ZLINK's package name and launch Activity
   - Which package/component handles switching to the original car ("原车") screen (give your guess and the evidence)
   - Which packages likely correspond to the reversing camera, CAN, MCU and the sidebar
   - Preinstalled apps sorted into three groups: must keep / possibly safe to disable / unsure
5. Write the report to recon/REPORT.md, and show me in the conversation the parts that contain no private information such as serial numbers.
```

# Step 2: Install the launcher (after recon is complete)

```
Reply to me in Chinese.

First read AGENTS.md and the "Launcher app" section of README.md.
1. Based on recon/REPORT.md, write a config.json: set carplay to the ZLINK component and oem to the original-car switching component
   (if the original car screen is switched by a broadcast, use the broadcast: format). Show me the contents for confirmation.
2. Run launcher/build-and-install.sh (without --set-home) to install and open it for a trial.
3. After I confirm, push config.json with adb push, then pull current.json to verify.
4. Once my in-car trial looks good, run launcher/build-and-install.sh --set-home.
Before every step that changes the head unit, show me the commands first.
```
