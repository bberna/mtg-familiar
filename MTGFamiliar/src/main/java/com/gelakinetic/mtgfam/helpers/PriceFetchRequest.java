package com.gelakinetic.mtgfam.helpers;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.gelakinetic.mtgfam.helpers.database.CardDbAdapter;
import com.gelakinetic.mtgfam.helpers.database.DatabaseManager;
import com.gelakinetic.mtgfam.helpers.database.FamiliarDbException;
import com.octo.android.robospice.persistence.exception.SpiceException;
import com.octo.android.robospice.request.SpiceRequest;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import oauth.signpost.basic.UrlStringRequestAdapter;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.http.HttpParameters;
import oauth.signpost.http.HttpRequest;
import oauth.signpost.signature.HmacSha1MessageSigner;
import oauth.signpost.signature.SignatureBaseString;

/**
 * This class extends SpiceRequest for the type PriceInfo, and is used to fetch and cache price info asynchronously
 */
public class PriceFetchRequest extends SpiceRequest<PriceInfo> {

    private final int ENGINE_MKM = 1;
    private final int ENGINE_TCG = 0;


    private final String mCardName;
    private final String mSetCode;
    private final int mMultiverseID;
    private String mCardNumber;
    private final int mPriceEngine;

    /**
     * Default constructor
     *
     * @param cardName     The name of the card to look up
     * @param setCode      The set code (not TCG name) of this card's set
     * @param cardNumber   The collector's number of the card to look up
     * @param multiverseID The multiverse ID of the card to look up
     */
    public PriceFetchRequest(String cardName, String setCode, String cardNumber, int multiverseID, int PriceEngine) {
        super(PriceInfo.class);
        this.mCardName = cardName;
        this.mSetCode = setCode;
        this.mCardNumber = cardNumber;
        this.mMultiverseID = multiverseID;
        this.mPriceEngine = PriceEngine;
    }

    /**
     * This function takes a string of XML information and parses it into a Document object in order to extract prices
     *
     * @param xml The String of XML
     * @return a Document describing the XML
     * @throws ParserConfigurationException thrown by factory.newDocumentBuilder()
     * @throws SAXException                 thrown by  builder.parse()
     * @throws IOException                  thrown by  builder.parse()
     */
    private static Document loadXMLFromString(String xml) throws ParserConfigurationException, SAXException,
            IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        InputSource is = new InputSource(new StringReader(xml));
        return builder.parse(is);
    }

    /**
     * This runs as a service, builds the TCGplayer.com URL, fetches the data, and parses the XML
     *
     * @return a PriceInfo object with all the prices
     * @throws SpiceException If anything goes wrong with the database, URL, or connection, this will be thrown
     */
    @SuppressWarnings("SpellCheckingInspection")
    @Override
    public PriceInfo loadDataFromNetwork() throws SpiceException {
        switch(mPriceEngine){
            case ENGINE_MKM:
                return loadDataFromMKM();
            case ENGINE_TCG:
                return loadDataFromTCGPlayer();
        }
        return loadDataFromTCGPlayer();
    }


    private PriceInfo loadDataFromTCGPlayer() throws SpiceException {
        int retry = 2; /* try the fetch twice, once with accent marks and again without if it fails */
        SpiceException exception = null; /* Save the exception during while loops */
        SQLiteDatabase database = DatabaseManager.getInstance().openDatabase(false);
        while (retry > 0) {
            try {
				/* If the card number wasn't given, figure it out */
                if (mCardNumber == null) {
                    Cursor c = CardDbAdapter.fetchCardByNameAndSet(mCardName, mSetCode, CardDbAdapter.allData, database);
                    mCardNumber = c.getString(c.getColumnIndex(CardDbAdapter.KEY_NUMBER));
                    c.close();
                }

				/* Get the TCGplayer.com set name, why can't everything be consistent? */
                String tcgName = CardDbAdapter.getTcgName(mSetCode, database);
				/* Figure out the tcgCardName, which is tricky for split cards */
                String tcgCardName;
                int multiCardType = CardDbAdapter.isMultiCard(mCardNumber, mSetCode);
                if ((multiCardType == CardDbAdapter.TRANSFORM) && mCardNumber.contains("b")) {
                    tcgCardName = CardDbAdapter.getTransformName(mSetCode, mCardNumber.replace("b", "a"), database);
                }
                else if (mMultiverseID == -1 && (multiCardType == CardDbAdapter.SPLIT ||
                        multiCardType == CardDbAdapter.FUSE)) {
                    int multiID = CardDbAdapter.getSplitMultiverseID(mCardName, database);
                    if (multiID == -1) {
                        throw new FamiliarDbException(null);
                    }
                    tcgCardName = CardDbAdapter.getSplitName(multiID, database);
                }
                else if (mMultiverseID != -1 && (multiCardType == CardDbAdapter.SPLIT ||
                        multiCardType == CardDbAdapter.FUSE)) {
                    tcgCardName = CardDbAdapter.getSplitName(mMultiverseID, database);
                }
                else {
                    tcgCardName = mCardName;
                }

                if (retry == 1) {
                    tcgCardName = CardDbAdapter.removeAccentMarks(tcgCardName);
                }
				/* Build the URL */
                URL priceUrl = new URL("http://partner.tcgplayer.com/x3/phl.asmx/p?pk=MTGFAMILIA&s=" +
                        URLEncoder.encode(tcgName.replace(Character.toChars(0xC6)[0] + "", "Ae"), "UTF-8") + "&p=" +
                        URLEncoder.encode(tcgCardName.replace(Character.toChars(0xC6)[0] + "", "Ae"), "UTF-8")
                );

				/* Fetch the information from the web */
                HttpURLConnection urlConnection = (HttpURLConnection) priceUrl.openConnection();
                String result = IOUtils.toString(urlConnection.getInputStream());
                urlConnection.disconnect();

				/* Parse the XML */
                Document document = loadXMLFromString(result);
                Element element = document.getDocumentElement();


                try {
                    PriceInfo pi = new PriceInfo();
                    pi.mLow = Double.parseDouble(getString("lowprice", element));
                    pi.mAverage = Double.parseDouble(getString("avgprice", element));
                    pi.mHigh = Double.parseDouble(getString("hiprice", element));
                    pi.mFoilAverage = Double.parseDouble(getString("foilavgprice", element));
                    pi.mUrl = getString("link", element);
                    return pi;
                } catch (NumberFormatException error) {
                    exception = new SpiceException(error.getLocalizedMessage());
                } catch (DOMException e) {
                    exception = new SpiceException(e.getLocalizedMessage());
                }
            } catch (FamiliarDbException e) {
                exception = new SpiceException(e.getLocalizedMessage());
            } catch (MalformedURLException e) {
                exception = new SpiceException(e.getLocalizedMessage());
            } catch (IOException e) {
                exception = new SpiceException(e.getLocalizedMessage());
            } catch (ParserConfigurationException e) {
                exception = new SpiceException(e.getLocalizedMessage());
            } catch (SAXException e) {
                exception = new SpiceException(e.getLocalizedMessage());
            }
            retry--;
        }
        DatabaseManager.getInstance().closeDatabase();
        if (exception != null) {
            throw exception;
        }
        else {
            throw new SpiceException("CardNotFound");
        }
    }

    private PriceInfo loadDataFromMKM() throws SpiceException {
        SpiceException exception = null; /* Save the exception during while loops */
        SQLiteDatabase database = DatabaseManager.getInstance().openDatabase(false);
        try {
				/* If the card number wasn't given, figure it out */
            if (mCardNumber == null) {
                Cursor c = CardDbAdapter.fetchCardByNameAndSet(mCardName, mSetCode, CardDbAdapter.allData, database);
                mCardNumber = c.getString(c.getColumnIndex(CardDbAdapter.KEY_NUMBER));
                c.close();
            }

				/* Get the TCGplayer.com set name, why can't everything be consistent? */
            String tcgName = CardDbAdapter.getTcgName(mSetCode, database);
            String setFullName = CardDbAdapter.getFullSetName(mSetCode,database);
				/* Figure out the tcgCardName, which is tricky for split cards */
            String tcgCardName;
            int multiCardType = CardDbAdapter.isMultiCard(mCardNumber, mSetCode);
            if ((multiCardType == CardDbAdapter.TRANSFORM) && mCardNumber.contains("b")) {
                tcgCardName = CardDbAdapter.getTransformName(mSetCode, mCardNumber.replace("b", "a"), database);
            }
            else if (mMultiverseID == -1 && (multiCardType == CardDbAdapter.SPLIT ||
                    multiCardType == CardDbAdapter.FUSE)) {
                int multiID = CardDbAdapter.getSplitMultiverseID(mCardName, database);
                if (multiID == -1) {
                    throw new FamiliarDbException(null);
                }
                tcgCardName = CardDbAdapter.getSplitName(multiID, database).replace(" // "," ");
            }
            else if (mMultiverseID != -1 && (multiCardType == CardDbAdapter.SPLIT ||
                    multiCardType == CardDbAdapter.FUSE)) {
                tcgCardName = CardDbAdapter.getSplitName(mMultiverseID, database);
            }
            else {
                tcgCardName = mCardName;
            }

            tcgCardName = CardDbAdapter.removeAccentMarks(tcgCardName);

            /* Oauth debug
            Properties ppp =
                    new Properties(System.getProperties());

            ppp.setProperty("debug","true");
            System.setProperties(ppp);
            */

				/* Build the URL */
            String url = "https://sandbox.mkmapi.eu/ws/v1.1/products/"+tcgCardName.replace(" ","%20")+"/1/1/true";
            HttpGet requestPrice = new HttpGet(url);

            /*Oauth stuff*/
            CommonsHttpOAuthConsumer oauth_consumer = new CommonsHttpOAuthConsumer("pghpe9LK1pjWqN0H","RYNPx8Ielv1mzJH5JMMUaDg32KNVqFZ9");
            oauth_consumer.setSigningStrategy(new MKMAuthorizationHeaderSigningStrategy());
            oauth_consumer.setMessageSigner(new HmacSha1MessageSigner());
            oauth_consumer.setSendEmptyTokens(true);
            oauth_consumer.setTokenWithSecret("",""); //as we request only public information we usr empty token and secret token
            HttpParameters p = new HttpParameters();
            p.put("realm",url);
            oauth_consumer.setAdditionalParameters(p);

            oauth_consumer.sign(requestPrice);


/*          OAuth debug
            HttpParameters pt = oauth_consumer.getRequestParameters();
            HttpRequest request = new UrlStringRequestAdapter(url);
            SignatureBaseString s = new SignatureBaseString(request,pt);

            System.out.println("basestring="+s.generate());

            System.out.println(requestPrice.getURI());
            System.out.println("*** Request headers ***");
            Header[] requestHeaders = requestPrice.getAllHeaders();
            for(Header header : requestHeaders) {
                System.out.println(header.toString());
            }
*/



            int count = 1;

				/* Fetch the information from the web */
            ArrayList<String> results = new ArrayList<String>();
            HttpClient httpClient = new DefaultHttpClient();
            HttpResponse responsePrice = httpClient.execute(requestPrice);
            System.out.println("STATUS CODE"+responsePrice.getStatusLine().getStatusCode());
            String result = IOUtils.toString(responsePrice.getEntity().getContent());
            System.out.println(result);
            results.add(result);
            while(responsePrice.getStatusLine().getStatusCode() == 206){
                requestPrice = new HttpGet("https://sandbox.mkmapi.eu/ws/v1.1/products/"+tcgCardName.replace(" ","%20")+"/1/1/true/" + String.valueOf(count*100+1));
                System.out.println(requestPrice);
                responsePrice = httpClient.execute(requestPrice);


                if(responsePrice.getStatusLine().getStatusCode() == 204){
                    break;
                }
                else {
                    result = IOUtils.toString(responsePrice.getEntity().getContent());
                    results.add(result);
                }
                count++;
            }

            PriceInfo pi = null;
            for(String resp : results) {
				/* Parse the XML */
                Document document = loadXMLFromString(resp);
                NodeList expList = document.getElementsByTagName("expansion");
                for(int i=0; i<expList.getLength();i++) {
                    //System.out.println("explist:"+ expList.toString());
                    Node exp = expList.item(i);
                    //System.out.println("set:"+exp.getTextContent()+" tag:"+exp.getNodeName());
                    //System.out.println("nameset:"+setFullName);
                    if(exp.getTextContent().equals(setFullName)) {
                        //System.out.println("productList");
                        //we found the good product node
                        Node product = exp.getParentNode();
                        NodeList productChildren = product.getChildNodes();
                        for(int j=0; j<productChildren.getLength();j++) {
                            Node n = productChildren.item(j);
                            //System.out.println("product child : "+ n.getNodeName());
                            if(n.getNodeName().equals("priceGuide")) {
                                //System.out.println("priceList");
                                //finally, our price node
                                pi = new PriceInfo();
                                NodeList priceNodes = n.getChildNodes();
                                for (int k=0; k<priceNodes.getLength();k++){
                                    Node price = priceNodes.item(k);

                                    try {
                                        if(price.getNodeName().equals("LOW")) {
                                            //System.out.println("low:"+price.getTextContent());
                                            pi.mLow = Double.parseDouble(price.getTextContent());
                                        }
                                        else if(price.getNodeName().equals("AVG")) {
                                            pi.mAverage = Double.parseDouble(price.getTextContent());
                                        }
                                        else if(price.getNodeName().equals("LOWFOIL")){
                                            //only in v1.1
                                            pi.mFoilAverage = Double.parseDouble(price.getTextContent());
                                        }

                                    } catch (NumberFormatException error) {
                                        exception = new SpiceException(error.getLocalizedMessage());
                                    } catch (DOMException e) {
                                        exception = new SpiceException(e.getLocalizedMessage());
                                    }
                                }
                            }
                            else if(n.getNodeName().equals("productId")){
                                    /* Here we deal with productId if we want to use it */
                            }
                        }
                        DatabaseManager.getInstance().closeDatabase();
                        pi.mUrl = "";
                        return pi;
                    }
                }
            }
        } catch (OAuthExpectationFailedException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (OAuthCommunicationException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (OAuthMessageSignerException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (FamiliarDbException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (MalformedURLException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (IOException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (ParserConfigurationException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        } catch (SAXException e) {
            exception = new SpiceException(e.getLocalizedMessage());
        }


        DatabaseManager.getInstance().closeDatabase();
        if (exception != null) {
            throw exception;
        }
        else {
            throw new SpiceException("CardNotFound");
        }
    }

    /**
     * Get a string value out of an Element given a tag name
     *
     * @param tagName The name of the XML tag to extract a string from
     * @param element The Element containing XML information
     * @return The String in the XML with the corresponding tag
     */
    String getString(String tagName, Element element) {
        NodeList list = element.getElementsByTagName(tagName);
        if (list != null && list.getLength() > 0) {
            NodeList subList = list.item(0).getChildNodes();

            if (subList != null) {
                String returnValue = "";
                for (int i = 0; i < subList.getLength(); i++) {
                    returnValue += subList.item(i).getNodeValue();
                }
                return returnValue;
            }
        }
        return null;
    }
}