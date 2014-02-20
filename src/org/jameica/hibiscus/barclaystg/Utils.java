package org.jameica.hibiscus.barclaystg;

import java.util.ArrayList;
import java.util.List;

import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class Utils {

	
	  // Zerlegt einen String intelligent in max. 27 Zeichen lange Stücke
	  public static String[] parse(String line)
	  {
	    if (line == null || line.length() == 0)
	      return new String[0];
	    List<String> out = new ArrayList<String>();
	    String rest = line.trim();
	    int lastpos = 0;
	    while (rest.length() > 0) {
	    	if (rest.length() < 28) {
	    		out.add(rest);
	    		rest = "";
	    		continue;
	    	}
	    	int pos = rest.indexOf(' ', lastpos + 1);
	    	boolean zulang = (pos > 28) || pos == -1;
	    	// 1. Fall: Durchgehender Text mit mehr als 27 Zeichen ohne Space
	    	if (lastpos == 0 && zulang) {
	    		out.add(rest.substring(0, 27));
	    		rest = rest.substring(27).trim();
	    		continue;
	    	} 
	    	// 2. Fall Wenn der String immer noch passt, weitersuchen
	    	if (!zulang) {
	    		lastpos = pos;
	    		continue;
	    	}
	    	// Bis zum Space aus dem vorherigen Schritt den String herausschneiden
	    	out.add(rest.substring(0, lastpos));
	    	rest = rest.substring(lastpos + 1).trim();
	    	lastpos = 0;
	    }
	    return out.toArray(new String[0]);
	  }


//	public static void main(String [] args)
//	{
//		String[] s = new String[]{
//				"1", "1 2", "123456789012345678901234567890",
//				"123456789012345678901234567 890",
//				"1234567890123456789012345678 90",
//				"123456789012345678901234567 890",
//				"123456789012345678901234567 890 123456789012345678901234567 3342",
//				};
//		for (String t : s) {
//			System.out.println(t + ": " + Arrays.toString(parse(t)));
//		}
//	}

		
		public static List<HtmlAnchor> getLinks(HtmlPage page) {
			ArrayList<HtmlAnchor> l = new ArrayList<HtmlAnchor>();
			for (DomElement e : page.getElementsByTagName("a")) {
				if (e instanceof HtmlAnchor) {
					HtmlAnchor ahref = (HtmlAnchor) e;
					l.add(ahref);
				}
			}
			return l;
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
