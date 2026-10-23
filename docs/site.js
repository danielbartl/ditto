// ditto site: shared by index.html and guide.html

// copy buttons
document.querySelectorAll('.code').forEach(function (block) {
  var button = document.createElement('button');
  button.className = 'copy';
  button.type = 'button';
  button.textContent = 'Copy';
  button.addEventListener('click', function () {
    var text = block.querySelector('pre').innerText;
    navigator.clipboard.writeText(text).then(function () {
      button.textContent = 'Copied';
      setTimeout(function () { button.textContent = 'Copy'; }, 1500);
    });
  });
  block.appendChild(button);
});
