// Rhodes.cpp : Implementation of WinMain.


#include "stdafx.h"
#include "MainWindow.h"
#include "ServerHost.h"

extern "C" wchar_t* wce_mbtowc(const char* a);

class CRhodesModule : public CAtlExeModuleT< CRhodesModule >
{
public :
    // This method is called immediately before entering the message loop.
    // It contains initialization code for the application.
    // Returns:
    // S_OK => Success. Continue with RunMessageLoop() and PostMessageLoop().
    // S_FALSE => Skip RunMessageLoop(), call PostMessageLoop().
    // error code => Failure. Skip both RunMessageLoop() and PostMessageLoop().
    HRESULT PreMessageLoop(int nShowCmd) throw()
    {
        HRESULT hr = __super::PreMessageLoop(nShowCmd);
        if (FAILED(hr))
        {
            return hr;
        }
        // Note: In this sample, we don't respond differently to different hr success codes.

        // Allow only one instance of the application.
        // the "| 0x01" activates the correct owned window of the previous instance's main window
        HWND hWnd = FindWindow(CMainWindow::GetWndClassInfo().m_wc.lpszClassName, NULL);
        if (hWnd)
        {
            SetForegroundWindow( HWND( DWORD(hWnd) | 0x01 ) );
            return S_FALSE;
        }

        // Create the main application window
        m_appWindow.Create(NULL, CWindow::rcDefault, TEXT("Rhodes"));
        if (NULL == m_appWindow.m_hWnd)
        {
            return S_FALSE;
        }
        // Starting local server
        m_serverHost.Start(m_appWindow.m_hWnd);
        // Navigate to the home page
        //TCHAR _laodingpage[MAX_PATH];
		//LPTSTR lp = CHttpServer::Instance()->GetLoadingPage(_laodingpage);
        m_appWindow.Navigate2(_T("about:blank"));
        //m_appWindow.Navigate(CHttpServer::Instance()->GetStartPage());

        // Show the main application window
        m_appWindow.ShowWindow(nShowCmd);
        return S_OK;
    }

	void DoViewRefresh() {
		::PostMessage(m_appWindow.m_hWnd,WM_COMMAND,IDM_REFRESH,0);
	}

	void DoViewNavigate(char* url) {
		LPTSTR wcurl = wce_mbtowc(url);
		m_appWindow.Navigate2(wcurl);
		free(wcurl);
	}

	char* GetCurrentLocation() {
		return m_appWindow.GetCurrentLocation();
	}

	HWND GetManWindow() {
		return m_appWindow.m_hWnd;
	}

    void RunMessageLoop( ) throw( )
    {
        MSG msg;
        while (GetMessage(&msg, NULL, 0, 0))
        {
            if (!m_appWindow.TranslateAccelerator(&msg))
            {
                TranslateMessage(&msg);
                DispatchMessage(&msg);
            }
        }
        // Stop local server
        m_serverHost.Stop();
    }

private:
    CMainWindow m_appWindow;
    CServerHost m_serverHost;
};

CRhodesModule _AtlModule;

//
extern "C" int WINAPI _tWinMain(HINSTANCE /*hInstance*/, HINSTANCE /*hPrevInstance*/,
                                LPTSTR /*lpCmdLine*/, int nShowCmd)
{
    return _AtlModule.WinMain(nShowCmd);
}

extern "C" HWND getMainWnd() {
	return _AtlModule.GetManWindow();
}

//Hook for ruby call to refresh web view

extern "C" void webview_refresh() {
	_AtlModule.DoViewRefresh();
}

//Sync hook to refresh the web view
extern "C" void perform_webview_refresh() {
	webview_refresh();
}

extern "C" void webview_navigate(char* url) {
	_AtlModule.DoViewNavigate(url);
}

extern "C" char* get_current_location() {
	return _AtlModule.GetCurrentLocation();
}

extern "C" char* webview_current_location() {
	return get_current_location();
}

