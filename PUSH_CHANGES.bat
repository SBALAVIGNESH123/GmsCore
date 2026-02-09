@echo off
echo ===================================================
echo Pushing Refactored RCS Code to GitHub
echo ===================================================
echo.
echo Renaming current branch to rcs-bounty-implementation...
git branch -m master rcs-bounty-implementation 2>nul
git branch -m rcs-bounty-implementation 2>nul

echo.
echo Pushing to origin/rcs-bounty-implementation (Force Push)...
git push -f origin rcs-bounty-implementation

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Push failed. You might need to authenticate.
    echo.
) else (
    echo.
    echo [SUCCESS] Code pushed successfully!
    echo.
)
pause
