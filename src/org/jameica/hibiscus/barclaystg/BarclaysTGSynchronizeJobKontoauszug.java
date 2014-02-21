package org.jameica.hibiscus.barclaystg;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.annotation.Resource;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.ThreadedRefreshHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSelect;
import com.gargoylesoftware.htmlunit.html.HtmlTable;
import com.gargoylesoftware.htmlunit.html.HtmlTableRow;

import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Implementierung des Kontoauszugsabruf fuer AirPlus.
 * Von der passenden Job-Klasse ableiten, damit der Job gefunden wird.
 */
public class BarclaysTGSynchronizeJobKontoauszug extends SynchronizeJobKontoauszug implements BarclaysTGSynchronizeJob
{
	private final static I18N i18n = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getI18N();

	@Resource
	private BarclaysTGSynchronizeBackend backend = null;

	private DateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN);
	/**
	 * @see org.jameica.hibiscus.barclaystg.AirPlusSynchronizeJob#execute()
	 */
	@Override
	public void execute() throws Exception
	{
		Konto konto = (Konto) this.getContext(CTX_ENTITY); // wurde von AirPlusSynchronizeJobProviderKontoauszug dort abgelegt

		Logger.info("Rufe Umsätze ab für " + backend.getName());

		////////////////
		String username = konto.getKundennummer();
		String password = konto.getMeta(BarclaysTGSynchronizeBackend.PROP_PASSWORD, null);
		if (username == null || username.length() == 0)
			throw new ApplicationException(i18n.tr("Bitte geben Sie Ihren Zugangs-Code in den Synchronisationsoptionen ein"));

		if (password == null || password.length() == 0)
			password = Application.getCallback().askPassword("Barclays Bank");

		Logger.info("username: " + username);
		////////////////


		List<Umsatz> fetched = doOneAccount(konto, username, password);

		Date oldest = null;

		// Ermitteln des aeltesten abgerufenen Umsatzes, um den Bereich zu ermitteln,
		// gegen den wir aus der Datenbank abgleichen
		for (Umsatz umsatz:fetched)
		{
			if (oldest == null || umsatz.getDatum().before(oldest))
				oldest = umsatz.getDatum();
		}


		// Wir holen uns die Umsaetze seit dem letzen Abruf von der Datenbank
		GenericIterator existing = konto.getUmsaetze(oldest,null);
		for (Umsatz umsatz:fetched)
		{
			if (existing.contains(umsatz) != null)
				continue; // haben wir schon

			// Neuer Umsatz. Anlegen
			umsatz.store();

			// Per Messaging Bescheid geben, dass es einen neuen Umsatz gibt. Der wird dann sofort in der Liste angezeigt
			Application.getMessagingFactory().sendMessage(new ImportMessage(umsatz));
		}

		konto.store();

		// Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat
		Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
	}

	public List<Umsatz> doOneAccount(Konto konto, String username, String password) throws Exception {
		List<Umsatz> umsaetze = new ArrayList<Umsatz>();

		final WebClient webClient = new WebClient(BrowserVersion.INTERNET_EXPLORER_8);
		webClient.setCssErrorHandler(new SilentCssErrorHandler());
		webClient.setRefreshHandler(new ThreadedRefreshHandler());

		// Login-Page und Login
		HtmlPage page = webClient.getPage("https://service.barclays.de/");
		HtmlForm form = page.getForms().get(0);
		((HtmlInput) page.getHtmlElementById("b_usr")).setValueAttribute(username);
		((HtmlInput) page.getHtmlElementById("b_pwd")).setValueAttribute(password);
		final HtmlButton button = form.getButtonByName("post");
		page = button.click();
		
		// Kontostand extrahierne und Umsatzlink suchen
		@SuppressWarnings("unchecked")
		List<HtmlTable> kontentabellen = (List<HtmlTable>) page.getByXPath( "//table[@id='konten']");
		if (kontentabellen.size() != 1) {
			throw new ApplicationException(i18n.tr("Konnte die Kontenübersicht nicht finden. (Username/Pwd falsch?)"));
		}
		HtmlAnchor ahref = null;
		HtmlTable kontentabelle = kontentabellen.get(0);
		for (int i = 0; i < kontentabelle.getRowCount(); i++) {
			if (kontentabelle.getCellAt(i, 0).asText().equals(konto.getKontonummer())) {
				List<?> x = kontentabelle.getRow(i).getByXPath( "//a[@title='Umsätze anzeigen']");
				if (x.size() != 1) {
					throw new ApplicationException(i18n.tr("Konnte den Kontostand nicht ermitteln (" + x.size() + ")! Zugangsdaten falsch?"));
				}
				ahref = (HtmlAnchor) x.get(0);
				konto.setSaldo(string2float(kontentabelle.getCellAt(i,  5).asText().replace(" ", "").trim()));
				break;
			}
		}
		if (ahref == null) {
			throw new ApplicationException(i18n.tr("Link für die Umsätze nicht gefunden!"));
		}
		page = ahref.click();

		// Datumsbereich der Umsätze ändern
		HtmlSelect select = (HtmlSelect) page.getElementById("duration_field");
		select.setSelectedAttribute("360", true);
		@SuppressWarnings("unchecked")
		List<HtmlButton> submitButton = (List<HtmlButton>) page.getByXPath( "//button[@value='weiter']");
		if (submitButton.size() != 1) {
			throw new ApplicationException(i18n.tr("Konnte den Datumsbereich  nicht ändern!"));
		}
		page = submitButton.get(0).click();
		
		// Alle Unterseiten mit Umsätzen durchgehen
		int pagenr = 1;
		try {
			while (true) {
				@SuppressWarnings("unchecked")
				List<HtmlTable> tabellen = (List<HtmlTable>) page.getByXPath( "//table[@id='umsaetze']");
				if (tabellen.size() != 1) {
					throw new ApplicationException(i18n.tr("Konnte die Umsätze aus Tabelle nicht extrahieren."));
				}
				HtmlTable tab = tabellen.get(0);
				for (int zeileIdx = 1; zeileIdx < tab.getRowCount(); zeileIdx++) {
					HtmlTableRow zeile = tab.getRow(zeileIdx);
					if (zeile.getCells().size() != 4) {
						continue;
					}
					String betrag = zeile.getCell(3).asText();
					String wertstellung = zeile.getCell(1).asText();
					String datum = zeile.getCell( 0).asText();
					String verwendungszweck = zeile.getCell(2).asText();

					Umsatz newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
					newUmsatz.setKonto(konto);
					newUmsatz.setBetrag(string2float(betrag));
					newUmsatz.setDatum(df.parse(datum));
					newUmsatz.setValuta(df.parse(wertstellung));
					newUmsatz.setWeitereVerwendungszwecke(Utils.parse(verwendungszweck));
					umsaetze.add(newUmsatz);
					
				}
				pagenr++;
				page = (page.getAnchorByText("" + pagenr)).click();
			} 
		} catch (ElementNotFoundException e) {
			System.out.println("Page " + pagenr + " nicht gefunden!");

		}
		webClient.closeAllWindows();
		return umsaetze;
	}
	


	
	/**
	 * - Tausender Punkte entfernen
	 * - Komma durch Punkt ersetzen
	 * @param s
	 * @return
	 */
	public static float string2float(String s) {
		try {
			return Float.parseFloat(s.replace(".", "").replace(",", "."));
		} catch (Exception e) {
			throw new RuntimeException("Cannot convert " + s + " to float");
		}

	}


}

	


