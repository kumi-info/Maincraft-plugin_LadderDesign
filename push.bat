@echo off
setlocal

echo === Push to PRIVATE (origin) - all files ===
git push origin main

echo.
echo === Push to PUBLIC (public) - excluding docs/internal ===
git checkout -b _public_temp main
git rm -r --cached docs/internal 2>NUL
git commit -m "sync: exclude internal docs" --allow-empty
git push public _public_temp:main --force
REM rm --cached で docs/internal が未追跡になり通常の checkout が失敗するため -f で戻す
REM （作業ツリーの docs/internal は main と同一内容なので上書きしても安全）
git checkout -f main
git branch -D _public_temp

echo.
echo Done!
pause
