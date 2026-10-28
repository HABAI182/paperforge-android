package cn.paperforge.archive;
import java.io.*;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.validation.profiles.*;
/** Reads the unmodified bundled veraPDF XML using DOM, avoiding JAXB's AWT bootstrap. */
public final class AndroidProfiles {
 private static Element child(Element parent,String name){
  if(parent==null)return null;
  for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling())
   if(node instanceof Element&&name.equals(node.getLocalName()))return (Element)node;
  return null;
 }
 private static List<Element> children(Element parent,String name){
  List<Element> result=new ArrayList<>();if(parent==null)return result;
  for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling())
   if(node instanceof Element&&name.equals(node.getLocalName()))result.add((Element)node);
  return result;
 }
 private static String text(Element parent,String name){Element found=child(parent,name);return found==null?"":found.getTextContent();}
 private static String attr(Element e,String name){return e.hasAttribute(name)?e.getAttribute(name):null;}
 public static ValidationProfile load(PDFAFlavour flavour) throws Exception {
  String file="/org/verapdf/pdfa/validation/"+flavour.name().replace("PDFA_","PDFA-").replace("_","")+".xml";
  try(InputStream stream=Profiles.class.getResourceAsStream(file)){
   if(stream==null)throw new IOException("Missing profile "+file);
   DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
   Element root=factory.newDocumentBuilder().parse(stream).getDocumentElement();
   if(!root.getAttribute("flavour").equals(flavour.name()))throw new IOException("Profile flavour mismatch");
   Element details=child(root,"details");
   ProfileDetails info=Profiles.profileDetailsFromValues(text(details,"name"),text(details,"description"),details.getAttribute("creator"),Date.from(Instant.parse(details.getAttribute("created"))));
   Set<Rule> rules=new LinkedHashSet<>();Set<Variable> variables=new LinkedHashSet<>();
   for(Element r:children(child(root,"rules"),"rule")){
    Element id=child(r,"id"),error=child(r,"error");
    RuleId ruleId=Profiles.ruleIdFromValues(PDFAFlavour.Specification.valueOf(id.getAttribute("specification")),id.getAttribute("clause"),Integer.parseInt(id.getAttribute("testNumber")));
    List<ErrorArgument> args=new ArrayList<>();
    for(Element arg:children(child(error,"arguments"),"argument"))args.add(ErrorArgumentImpl.fromValues(arg.getTextContent(),attr(arg,"name"),attr(arg,"argumentValue")));
    List<Reference> refs=new ArrayList<>();
    for(Element ref:children(child(r,"references"),"reference"))refs.add(Profiles.referenceFromValues(ref.getAttribute("specification"),ref.getAttribute("clause")));
    rules.add(Profiles.ruleFromValues(ruleId,r.getAttribute("object"),r.hasAttribute("deferred")?Boolean.valueOf(r.getAttribute("deferred")):null,attr(r,"tags"),text(r,"description"),text(r,"test"),Profiles.errorFromValues(text(error,"message"),args),refs));
   }
   for(Element v:children(child(root,"variables"),"variable"))variables.add(Profiles.variableFromValues(v.getAttribute("name"),v.getAttribute("object"),text(v,"defaultValue"),text(v,"value")));
   return Profiles.profileFromValues(flavour,info,text(root,"hash"),rules,variables);
  }
 }
}
