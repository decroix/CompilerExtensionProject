object Example2 {

  def withError(b : Int) : Int = {
    if(b < 0){
      error("less than 0")
    }
    else { b }
  }

  val a = withError(-1);
  val b = withError(2);
  Std.printString("a and b generated!")
}
