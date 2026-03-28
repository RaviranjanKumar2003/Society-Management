import EnterCode from "./Screens/EnterCode";
import Visitor from "./Screens/Visitor";
import FrequentVisitor from "./Screens/FrequentVisitor";
import Notice from "./Screens/SNotice";
import Guest from "./Screens/Guest";
import Delivery from "./screens/Delivery.jsx";
import Cab from "./Screens/Cab";
import More from "./Screens/More"
import AddData from "./Screens/AddData";
import KidCheck from "./Screens/KidCheck";
import ThreeDot from "./Screens/ThreeDot";

function ScreenRenderer({ activeTab ,setActiveTab }) {
  switch (activeTab) {
    case "ENTER_CODE": return <EnterCode />;
    case "VISITOR": return <Visitor />;
    case "FREQ_VISITOR": return <FrequentVisitor />;
    case "NOTICE": return <Notice />;
    case "GUEST": return <Guest />;
    case "DELIVERY": return <Delivery />;
    case "CAB": return <Cab />;
    case "MORE": return <More setActiveTab={setActiveTab} />;
    case "THREE_DOT": return <ThreeDot setActiveTab={setActiveTab} />;
    case "ADD_DATA": return <AddData setActiveTab={setActiveTab} />;
    case "KID_CHECK": return <KidCheck setActiveTab={setActiveTab} />;
    default: return null;
  }
}

export default ScreenRenderer;
